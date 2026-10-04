import test from 'node:test';
import assert from 'node:assert/strict';
import {MediaFactoryClient,outputPath} from '../skills/_shared/client.mjs';
test('plan retries preserve caller operation ID and no implicit execution',async()=>{
 const calls=[];const client=new MediaFactoryClient({fetchImpl:async(url,init)=>{calls.push({url:String(url),...init});return {ok:true,json:async()=>({id:'a',status:'PLANNED'})}}});
 const request={operationId:'stable',skillName:'create-collection',input:{generate:false}};
 await client.plan(request);await client.plan(request);
 assert.equal(calls.length,2);assert.equal(calls[0].body,calls[1].body);assert.ok(calls.every(c=>c.url.endsWith('/executions')));
});
test('monitor stops at human review rather than reporting success',async()=>{
 let n=0;const client=new MediaFactoryClient({fetchImpl:async()=>({ok:true,json:async()=>({status:++n===1?'RUNNING':'WAITING_FOR_APPROVAL'})})});
 const result=await client.monitor('00000000-0000-0000-0000-000000000001',{sleep:async()=>{}});assert.equal(result.status,'WAITING_FOR_APPROVAL');assert.equal(n,2);
});
test('errors never print bearer tokens or raw remote text',async()=>{
 const client=new MediaFactoryClient({token:'secret-value',fetchImpl:async()=>({ok:false,status:502,text:async()=>'secret-value'})});
 await assert.rejects(client.request('/api/projects'),e=>!e.message.includes('secret-value'));
});
test('credential URLs and path traversal are rejected',async()=>{
 assert.throws(()=>new MediaFactoryClient({baseUrl:'https://user:secret@example.org'}));
 const client=new MediaFactoryClient();await assert.rejects(client.request('/api/../secrets'));
 for(const name of ['../outside.zip','C:\\secret.zip','/tmp/out.zip','a/../../out'])assert.throws(()=>outputPath('exports',name));
 assert.ok(outputPath('exports','stock-package.zip').endsWith('stock-package.zip'));
});
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
test('exports verify checksum and never overwrite an existing local file',async()=>{
 const root=await mkdtemp(join(tmpdir(),'skill-export-'));
 const bytes=Buffer.from('immutable mock package');const checksum=createHash('sha256').update(bytes).digest('hex');
 const client=new MediaFactoryClient({fetchImpl:async()=>({ok:true,arrayBuffer:async()=>bytes})});
 const args=['stock','00000000-0000-0000-0000-000000000001',root,'fixture.zip'];
 try{await assert.rejects(client.downloadExport(...args,'0'.repeat(64)),/checksum mismatch/);const result=await client.downloadExport(...args,checksum);assert.deepEqual(await readFile(result.path),bytes);await assert.rejects(client.downloadExport(...args,checksum),/EEXIST/);}finally{await rm(root,{recursive:true,force:true});}
});
