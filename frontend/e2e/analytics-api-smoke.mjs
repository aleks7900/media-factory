import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
const root=process.env.MEDIA_FACTORY_API??'http://localhost:8080';
const get=async path=>{const r=await fetch(root+path);assert.equal(r.status,200,`${path}: ${r.status}`);return r.json();};
const health=await get('/actuator/health');assert.equal(health.status,'UP');
const endpointChecks=[];
for(const route of ['overview','assets','collections','prompts','providers','platforms','costs','revenue','experiments','timeseries','diagnostics','data-quality','provider-operations','processing','jobs']) {
  await get('/api/v1/analytics/'+route);endpointChecks.push(route);
}
const assets=await get('/api/assets');const mediaChecks=[];
for(const asset of assets) {
  await get(`/api/v1/analytics/assets/${asset.id}`);
  const r=await fetch(`${root}/api/assets/${asset.id}/content`);assert.equal(r.status,200);
  const bytes=Buffer.from(await r.arrayBuffer());const sha=createHash('sha256').update(bytes).digest('hex');
  assert.equal(sha,asset.sha256,`Original checksum ${asset.id}`);
  mediaChecks.push({assetId:asset.id,bytes:bytes.length,sha256:sha});
}
await mkdir('test-results',{recursive:true});
await writeFile('test-results/analytics-api-smoke.json',JSON.stringify({health,endpointChecks,originalMedia:mediaChecks},null,2));
console.log(`PASS: ${endpointChecks.length} analytics endpoints; ${mediaChecks.length} original media SHA-256 checksums verified.`);
