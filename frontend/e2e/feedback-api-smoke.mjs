import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
const root=process.env.MEDIA_FACTORY_API??'http://localhost:8080';
const get=async path=>{const r=await fetch(root+path);assert.equal(r.status,200,`${path}: ${await r.clone().text()}`);return r.json();};
const health=await get('/actuator/health');assert.equal(health.status,'UP');
const routes=['overview','attributes','findings','hypotheses','analyses','experiments','results','learnings','saturation','data-quality','jobs'];
for(const route of routes)await get('/api/v1/feedback/'+route);
const taxonomy=await get('/api/v1/feedback/attributes');assert.ok(taxonomy.length>=22);
const assets=await get('/api/assets');
const asset=assets.find(a=>a.media_type?.startsWith('image/'));
let job;
if(asset){
  const response=await fetch(`${root}/api/v1/feedback/assets/${asset.id}/visual-features/extract`,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':`task11-smoke:${asset.id}:visual-v1`},body:JSON.stringify({extractorVersion:'visual-v1'})});
  assert.equal(response.status,200);job=await response.json();
  for(let i=0;i<60;i++){
    job=await get(`/api/v1/feedback/jobs/${job.id}`);
    if(['SUCCEEDED','FAILED'].includes(job.status))break;
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
  assert.equal(job.status,'SUCCEEDED',JSON.stringify(job));
  const features=await get(`/api/v1/feedback/assets/${asset.id}/visual-features`);
  assert.ok(features.history.length>=8);
}
await mkdir('test-results',{recursive:true});
await writeFile('test-results/feedback-api-smoke.json',JSON.stringify({health,routes,taxonomyDefinitions:taxonomy.length,extractionJob:job??null},null,2));
console.log(`PASS: ${routes.length} Feedback endpoints, taxonomy and ${asset?'durable mock extraction':'no available image fixture'}.`);
