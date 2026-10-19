"""Real headless Chromium + Vue + real HTTP backend; fake audio device only."""
import json, os, pathlib, subprocess, time, urllib.request, sys
from playwright.sync_api import sync_playwright, expect

root=pathlib.Path.cwd(); out=root/'qa30-results'
f=json.loads((out/'browser-fixture.json').read_text())
ru=json.loads((root/'frontend/src/i18n/ru.json').read_text())['attempt']
en=json.loads((root/'frontend/src/i18n/en.json').read_text())['attempt']
results=[]
disabled="--disabled" in sys.argv
server=subprocess.Popen(['npm','run','dev'],cwd=root/'frontend',stdout=open(out/'vite.log','w'),stderr=subprocess.STDOUT)
def record(case,variant,fn):
    try: fn(); r=dict(case=case,variant=variant,status='PASS')
    except Exception as e: r=dict(case=case,variant=variant,status='FAIL',detail=str(e))
    results.append(r); print(json.dumps(r),flush=True)
    (out/('browser-disabled-results.json' if disabled else 'browser-results.json')).write_text(json.dumps(results,indent=2))
def history():
    return json.load(urllib.request.urlopen('http://127.0.0.1:8080'+f['base'],timeout=15))
try:
    for _ in range(100):
        try:
            if urllib.request.urlopen('http://127.0.0.1:5173',timeout=2).status==200: break
        except OSError: time.sleep(.2)
    with sync_playwright() as pw:
        sine=out/'fake-microphone.wav'
        subprocess.run(['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','15','-y',str(sine)],check=True)
        browser=pw.chromium.launch(args=['--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream','--use-file-for-fake-audio-capture='+str(sine)])
        context=browser.new_context(permissions=['microphone'])
        context.add_init_script('localStorage.setItem("guitar-mentor.learnerId",'+json.dumps(f['learner'])+');localStorage.setItem('+json.dumps('guitar-mentor.diagnostic.'+f['learner']+'.ACOUSTIC_GUITAR')+','+json.dumps(f['session'])+');')
        page=context.new_page(); page.goto('http://127.0.0.1:5173/diagnostics')
        if disabled:
            def disabled_form():
                expect(page.locator('#attempt-file')).to_be_disabled(timeout=20000)
                expect(page.locator('#attempt-comment')).to_be_disabled()
                expect(page.locator('.homework-form button[type=submit]')).to_be_disabled()
                expect(page.get_by_role('button',name=ru['startRecording'],exact=True)).to_be_disabled()
                expect(page.locator('.homework-history .attempt-card')).to_have_count(len(history()))
            record('TC30-08','actual uploads=false backend: browser controls disabled, history visible',disabled_form)
        else:
            expect(page.locator('#attempt-file')).to_be_enabled(timeout=20000)
            def file_upload():
                n=len(history())
                page.locator('#attempt-file').set_input_files(str(out/'fixture.wav'))
                page.locator('#attempt-comment').fill('browser file upload')
                assert len(history())==n
                page.locator('.homework-form button[type=submit]').click()
                expect(page.locator('#attempt-file')).to_have_value('')
                expect(page.locator('#attempt-comment')).to_have_value('')
                expect(page.locator('.homework-history .attempt-card')).to_have_count(n+1,timeout=20000)
                assert len(history())==n+1
            record('TC30-04','Chromium file selection, explicit submit, cleared native input',file_upload)
            def recorder():
                n=len(history())
                page.get_by_role('button',name=ru['startRecording'],exact=True).click()
                expect(page.get_by_role('timer')).to_be_visible(timeout=10000)
                page.wait_for_function('Number(document.querySelector("[role=meter]")?.getAttribute("aria-valuenow")) > 0')
                page.wait_for_timeout(2200)
                assert len(history())==n
                page.get_by_role('button',name=ru['stopRecording'],exact=True).click()
                audio=page.locator('.recorded-preview audio')
                expect(audio).to_be_visible(timeout=10000)
                audio.evaluate('(a) => a.play()')
                page.wait_for_function('document.querySelector(".recorded-preview audio").currentTime > 0')
                assert len(history())==n
                page.locator('.homework-form button[type=submit]').click()
                expect(page.locator('.recorded-preview')).to_have_count(0)
                expect(page.locator('.homework-history .attempt-card')).to_have_count(n+1,timeout=20000)
            record('TC30-06','fake physical device: MediaRecorder, level>0, preview playback, explicit submit',recorder)
            def language():
                n=len(history()); ids=[a['id'] for a in history()]
                expect(page.locator('.homework-status')).to_have_text(ru['noAiFeedback'])
                page.locator('select[aria-label=Language]').select_option('en')
                expect(page.locator('.homework-status')).to_have_text(en['noAiFeedback'])
                assert [a['id'] for a in history()]==ids
                page.reload(); expect(page.locator('.attempt-card')).to_have_count(n,timeout=15000)
                assert [a['id'] for a in history()]==ids
            record('TC30-25','RU/EN truthful technical status, history survives language change/F5',language)
            def private_fields():
                text=page.locator('.homework-panel').inner_text()
                assert str(out) not in text and 'media_key' not in text
                assert len(history())==page.locator('.homework-history .attempt-card').count()
            record('TC30-07','real browser F5 restores same server history',private_fields)
        browser.close()
finally:
    server.terminate()
    try: server.wait(timeout=10)
    except subprocess.TimeoutExpired: server.kill(); server.wait()
assert results and all(x['status']=='PASS' for x in results),'browser acceptance failed'
