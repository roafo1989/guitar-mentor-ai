"""Independent acceptance run. Only synthetic fixtures and runner-local PostgreSQL/files."""
import argparse, hashlib, io, json, os, pathlib, struct, subprocess, time, uuid, urllib.request, urllib.error, wave

ROOT = pathlib.Path.cwd()
OUT = ROOT / 'qa30-results'
OUT.mkdir(exist_ok=True)
MEDIA = OUT / 'media'
MEDIA.mkdir(exist_ok=True)
RESULTS = []
ORIGIN = 'http://127.0.0.1:8080'
CURRENT = None

def check(case, description, action):
    try:
        action()
        item = dict(case=case, variant=description, status='PASS')
    except Exception as e:
        item = dict(case=case, variant=description, status='FAIL', detail=str(e))
    RESULTS.append(item)
    print(json.dumps(item), flush=True)
    (OUT / 'http-results.json').write_text(json.dumps(RESULTS, indent=2))

def request(method, path, data=None, headers=None):
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        headers = {'Content-Type':'application/json'}
    req = urllib.request.Request(ORIGIN + path, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=35) as res: code, body = res.status, res.read()
    except urllib.error.HTTPError as e: code, body = e.code, e.read()
    try: body = json.loads(body)
    except (ValueError, UnicodeDecodeError): pass
    return code, body

def api(method, path, data=None):
    code, body = request(method, path, data)
    assert code in (200,201), (method,path,code,body)
    return body

def wav(seconds=1, target=None):
    buffer=io.BytesIO()
    with wave.open(buffer,'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(8000); f.writeframes(bytes(int(seconds*8000)*2))
    content=buffer.getvalue()
    if target:
        extra=target-len(content)-8
        content=content[:4]+struct.pack('<I',target-8)+content[8:]+b'JUNK'+struct.pack('<I',extra)+bytes(extra)
    return content

def upload(base, content, name='qa.wav', comment='', request_id=None):
    boundary='qa30'+uuid.uuid4().hex
    fields={'requestId':request_id or str(uuid.uuid4()),'comment':comment}
    data=b''
    for key,value in fields.items():
        data+=f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
    data+=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+content+f'\r\n--{boundary}--\r\n'.encode()
    return request('POST',base,data,{'Content-Type':'multipart/form-data; boundary='+boundary})

def terminal(base, attempt_id):
    for _ in range(80):
        item=next(a for a in api('GET',base) if a['id']==attempt_id)
        if item['status'] in ('PROCESSED','FAILED'): return item
        time.sleep(.5)
    raise AssertionError('worker timeout')

def start(jar, enabled=True):
    global CURRENT
    env=os.environ.copy(); env['GUITAR_MENTOR_MEDIA_PATH']=str(MEDIA)
    env['GUITAR_MENTOR_PROCESS_POLL_MS']='100'
    env.pop('GUITAR_MENTOR_ENABLE_UPLOADS',None)
    if enabled is not None: env['GUITAR_MENTOR_ENABLE_UPLOADS']=str(enabled).lower()
    log=open(OUT/f'boot-{time.time_ns()}.log','wb')
    CURRENT=subprocess.Popen(['java','-jar',str(pathlib.Path(jar).resolve())],env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(160):
        if CURRENT.poll() is not None: raise RuntimeError('backend exited early')
        try:
            if request('GET','/actuator/health')==(200,{'status':'UP'}): return
        except OSError: pass
        time.sleep(.5)
    raise RuntimeError('backend readiness timeout')

def stop():
    global CURRENT
    if CURRENT:
        CURRENT.terminate()
        try: CURRENT.wait(timeout=20)
        except subprocess.TimeoutExpired: CURRENT.kill(); CURRENT.wait()
        CURRENT=None

def setup():
    learner=api('POST','/api/v1/learners',dict(displayName='QA Synthetic Student',instrumentCode='ACOUSTIC_GUITAR',techniqueCode='FINGERSTYLE',preferences=[]))['id']
    sessions=f'/api/v1/learners/{learner}/diagnostic-sessions'
    session=api('POST',sessions,dict(instrumentCode='ACOUSTIC_GUITAR'))['id']
    api('POST',sessions+'/'+session+'/continue')
    task=api('POST',sessions+'/'+session+'/tasks',dict(exerciseCode='GM-EB01'))['id']
    return learner,session,task,f'{sessions}/{session}/tasks/{task}/attempts'

def files(): return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in MEDIA.iterdir()}

def assert_equal(a,b): assert a==b, (a,b)

def main(args):
    start(args.baseline)
    learner,session,task,base=setup()
    req=str(uuid.uuid4()); code,old=upload(base,wav(),'legacy.wav','legacy comment',req)
    assert code==201
    old=terminal(base,old['id']); assert old['status']=='PROCESSED'
    legacy_history=api('GET',base); legacy_files=files()
    stop(); start(args.current)
    check('TC30-03','baseline 270238f -> #30: exact history and file SHA-256',lambda:(assert_equal(api('GET',base),legacy_history),assert_equal(files(),legacy_files)))
    def capabilities():
        c=api('GET',base+'/capabilities')
        assert c==dict(enabled=True,maxBytes=20971520,formats=['mp3','wav','ogg','webm']),c
        assert not any(k in json.dumps(api('GET',base)) for k in ['media_key','storagePath',str(MEDIA)])
    check('TC30-02','capabilities and no private response fields',capabilities)
    def duplicate():
        before=files(); count=len(api('GET',base))
        code,r=upload(base,wav(),request_id=req)
        assert code==201 and r['id']==old['id'] and r['number']==old['number']
        assert len(api('GET',base))==count and files()==before
    check('TC30-09','legacy requestId retry after upgrade',duplicate)
    for fmt,codec in [('wav','pcm_s16le'),('mp3','libmp3lame'),('ogg','libvorbis'),('webm','libopus')]:
        file=OUT/('fixture.'+fmt)
        subprocess.run(['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-f','lavfi','-i','anullsrc=channel_layout=mono:sample_rate=48000','-t','0.5','-c:a',codec,'-y',str(file)],check=True)
        def format_case(fmt=fmt,file=file):
            code,a=upload(base,file.read_bytes(),file.name)
            assert code==201,(code,a)
            item=terminal(base,a['id']); assert item['status']=='PROCESSED' and item['format']==fmt and item['audioDurationSeconds']>0 and not item['assessmentAvailable'],item
        check('TC30-05',fmt,format_case)
    for seconds in [120,125]:
        def duration_case(seconds=seconds):
            code,a=upload(base,wav(seconds)); assert code==201
            item=terminal(base,a['id'])
            if seconds==120: assert item['status']=='PROCESSED',item
            else: assert item['status']=='FAILED' and item['processingErrorCode']=='DURATION_EXCEEDED',item
        check('TC30-22',str(seconds)+' seconds',duration_case)
    for data,name,expected in [(b'','empty.wav','INVALID_AUDIO_SIZE'),(b'not an mp3','invalid.mp3','INVALID_AUDIO_CONTENT'),(b'text','file.txt','UNSUPPORTED_AUDIO_FORMAT')]:
        def invalid(data=data,name=name,expected=expected):
            before=files(); history=api('GET',base)
            code,r=upload(base,data,name)
            assert code==400 and r.get('code')==expected,(code,r)
            assert files()==before and api('GET',base)==history
        check('TC30-20',name,invalid)
    for size in [20971520,20971521]:
        def size_case(size=size):
            before=files(); count=len(api('GET',base))
            code,a=upload(base,wav(target=size),'boundary.wav')
            if size==20971520:
                assert code==201,(code,a)
                item=terminal(base,a['id']); assert item['status']=='PROCESSED',item
            else:
                assert code in (400,413),(code,a)
                assert files()==before and len(api('GET',base))==count
        check('TC30-21',str(size)+' bytes HTTP',size_case)
    for length in [2000,2001]:
        def comment_case(length=length):
            before=files(); count=len(api('GET',base))
            code,a=upload(base,wav(),comment='x'*length)
            if length==2000:
                assert code==201 and len(a['comment'])==2000
                terminal(base,a['id'])
            else:
                assert code==400 and a['code']=='COMMENT_TOO_LONG',(code,a)
                assert files()==before and len(api('GET',base))==count
        check('TC30-23',str(length)+' character comment',comment_case)
    other,other_session,other_task,_=setup()
    wrong=[base.replace(learner,other),base.replace(task,other_task)]
    for idx,path in enumerate(wrong):
        def ownership(path=path):
            before=files(); count=len(api('GET',base))
            for suffix in ['', '/capabilities']:
                code,r=request('GET',path+suffix); assert code>=400,(code,r)
                assert old['id'] not in json.dumps(r)
            code,r=upload(path,wav()); assert code>=400,(code,r)
            assert files()==before and len(api('GET',base))==count
        check('TC30-23','foreign ownership chain '+str(idx),ownership)
    (OUT/'browser-fixture.json').write_text(json.dumps(dict(learner=learner,session=session,task=task,base=base)))
    history=api('GET',base); snapshot=files()
    stop(); start(args.current)
    check('TC30-07','new JVM same PostgreSQL and file root',lambda:(assert_equal(api('GET',base),history),assert_equal(files(),snapshot)))
    check('TC30-09','requestId retry after JVM restart',duplicate)
    stop(); start(args.current,False)
    def flag_off():
        assert api('GET',base)==history
        assert api('GET',base+'/capabilities')['enabled'] is False
        before=files(); code,r=upload(base,wav())
        assert code==403 and r['code']=='UPLOAD_DISABLED',(code,r)
        assert files()==before and api('GET',base)==history
    check('TC30-08','explicit false',flag_off)
    if args.browser:
        subprocess.run(['python3','scripts/testing/qa30_browser.py','--disabled'],check=True)
    stop(); start(args.current,None)
    check('TC30-08','default disabled',flag_off)
    stop(); start(args.current,True)
    check('TC30-08','re-enabled',capabilities)
    (OUT/'browser-fixture.json').write_text(json.dumps(dict(learner=learner,session=session,task=task,base=base)))
    if args.browser:
        subprocess.run(['python3','scripts/testing/qa30_browser.py'],check=True)
    stop()
    assert all(r['status']=='PASS' for r in RESULTS),'some independent HTTP checks failed'

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--baseline',required=True); parser.add_argument('--current',required=True); parser.add_argument('--browser',action='store_true')
    try: main(parser.parse_args())
    finally: stop()
