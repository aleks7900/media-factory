import {MediaFactoryClient} from '../skills/_shared/client.mjs';
import {randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
const client=new MediaFactoryClient();const stamp=randomUUID();const report={stamp,startedAt:new Date().toISOString()};
const get=p=>client.request(p);const post=(p,body)=>client.request(p,{method:'POST',body});
const sleep=()=>new Promise(r=>setTimeout(r,3000));
async function wait(read,accept,label){let last;for(let n=0;n<200;n++){const r=await read();if(r.status!==last){console.log(`${label}: ${r.status}`);last=r.status;}if(await accept(r))return r;if(/FAILED|REJECTED|CANCELLED/.test(r.status))throw new Error(`${label} stopped: ${r.status}`);await sleep();}throw new Error(`${label} timed out`);}
async function approveFixtureQa(asset){if(!asset.current_review_id)return false;const review=await get(`/api/v1/reviews/${asset.current_review_id}`);if(review.execution_status!=='COMPLETED')return false;if(review.vision_provider!=='mock')throw new Error('Only mock fixture QA can be approved');if(review.final_decision==='NEEDS_REVIEW')await post(`/api/v1/reviews/${review.id}/approve`,{revision:review.revision,reasonCode:'OTHER',reasonText:'Explicit TASK-12 isolated mock stock acceptance fixture'});else if(review.final_decision!=='APPROVED')throw new Error('Rejected fixture remains rejected');return true;}
try{
 const providers=await get('/api/v1/providers/image');if(!providers.some(p=>p.id==='mock'&&p.default))throw new Error('Free mock default required');
 const project=await post('/api/projects',{name:`TASK-12 stock acceptance ${stamp.slice(0,8)}`,description:'Isolated mock source for prepare-stock acceptance'});report.projectId=project.id;
 const collection=await post('/api/v1/stock-collections',{projectId:project.id,title:`Geometric stock fixture ${stamp.slice(0,8)}`});report.collectionId=collection.id;
 const prompt=`Stock illustration: Precisely illuminated abstract ceramic geometric forms on a textured neutral background, balanced composition, detailed materials, no logos or lettering, fixture ${stamp}`;
 const concept=await post('/api/concepts',{collectionId:collection.id,name:'Ceramic geometry fixture',prompt});
 const response=await fetch(new URL('/api/generations',client.base),{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':`skills-source:${stamp}`},body:JSON.stringify({conceptId:concept.id,prompt,width:1024,height:1024})});if(!response.ok)throw new Error(`Source generation HTTP ${response.status}`);const generation=await response.json();report.generationId=generation.id;
 await wait(()=>get(`/api/generations/${generation.id}`),async g=>{const asset=(await get('/api/assets')).find(a=>a.generation_id===g.id);if(asset&&await approveFixtureQa(asset)){report.assetId=asset.id;return true;}return false;},'Source QA');
 const plan=await client.plan({skillName:'prepare-stock',operationId:`smoke:stock:${stamp}`,projectId:project.id,collectionId:collection.id,input:{assetIds:[report.assetId],profile:'STOCK_GENERIC',exportPackage:true,exportProfile:'GENERIC_CSV'}});report.executionId=plan.id;await client.action(plan.id,'start','Explicit free mock stock acceptance fixture');
 let metadataApproved=false;
 const result=await wait(()=>client.status(plan.id),async e=>{
   const item=e.items.find(i=>i.resource_type==='stock_productions');if(item){const production=await get(`/api/v1/stock-productions/${item.resource_id}`);if(production.source_asset_id!==report.assetId)throw new Error('Fixture scope mismatch');
    if(production.qa)await approveFixtureQa(production.qa);
    if(production.status==='METADATA_REVIEW'&&!metadataApproved){await post(`/api/v1/stock-productions/${production.id}/approve`,{revision:production.revision,acknowledgeWarnings:true});metadataApproved=true;report.productionId=production.id;}
   }
   if(e.status==='WAITING_FOR_APPROVAL'&&metadataApproved)await client.action(e.id,'resume','Explicit approval of own mock fixture metadata');
   return e.status==='COMPLETED';
 },'Stock workflow');report.execution=result;
 const item=result.items.find(i=>i.resource_type==='stock_exports');if(!item)throw new Error('No export');const archive=await get(`/api/v1/stock-exports/${item.resource_id}`);report.download=await client.downloadExport('stock',archive.id,'storage/data/skills-verification',`${archive.id}.zip`,archive.sha256);report.outcome='COMPLETED';
}catch(error){report.outcome='FAILED';report.error=error.message;console.error(error.message);process.exitCode=1;}
finally{await mkdir('storage/data/skills-verification',{recursive:true});await writeFile(`storage/data/skills-verification/stock-${stamp}.json`,JSON.stringify(report,null,2));console.log(`Stock evidence: ${stamp}`);}
