import test from 'node:test';
import assert from 'node:assert/strict';
import {isValidLogin,normalizeLogin,isValidPassword} from '../src/core/auth/credential-policy.ts';
test('#232 Latin/Cyrillic usernames share case and NFC canonicalization',()=>{
 for(const value of ['Роман_1989','Roman-Я.Ё','Élève','ЮЛІЯ','İlyas','J\u030CONA','abc','a'.repeat(64)])assert.equal(isValidLogin(value),true,value);
 assert.equal(normalizeLogin(' РОМАН_1989 '),'роман_1989');
 assert.equal(normalizeLogin('И\u0306оЖ'),normalizeLogin('ЙоЖ'));
 assert.equal(normalizeLogin('J\u030CONA'),'ǰona');
 for(const value of ['','ab','a'.repeat(65),'a b','_abc','.abc','a@b','аб😀','用户甲','ab\u200Dcd','a\nb','a\0b','ab\u0301.\u0301'])assert.equal(isValidLogin(value),false,value);
});
test('#232 new passwords accept 6 through 128 characters in every workflow',()=>{
 for(const value of ['123456','x'.repeat(128),'😀'.repeat(6)])assert.equal(isValidPassword(value),true);
 for(const value of ['12345','x'.repeat(129),'😀'.repeat(5)])assert.equal(isValidPassword(value),false);
});
