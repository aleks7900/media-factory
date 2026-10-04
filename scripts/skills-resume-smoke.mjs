import {MediaFactoryClient} from '../skills/_shared/client.mjs';
import {readFile,writeFile} from 'node:fs/promises';
const [file,flag]=process.argv.slice(2);if(flag!=='--approve-own-mock-fixture')throw new Error('Explicit isolated fixture approval flag required');
const report=JSON.parse(await readFile(file,'utf8'));const client=new MediaFactoryClient();
const execution=await client.status(report.wallpapers.id);
if(execution.project_id!==report.projectId||!execution.operation_id.startsWith('smoke:'))throw new Error('Not this harness fixture');
const item=execution.items.find(i=>i.resource_type==='wallpaper_productions');
const production=await client.request(`/api/v1/wallpaper-productions/${item.resource_id}`);
const review=await client.request(`/api/v1/reviews/${production.qa.current_review_id}`);
if(review.vision_provider!=='mock'||review.asset_id!==production.master_asset_id)throw new Error('Only the harness mock asset may be approved');
if(review.final_decision==='NEEDS_REVIEW')await client.request(`/api/v1/reviews/${review.id}/approve`,{method:'POST',body:{revision:review.revision,reasonCode:'OTHER',reasonText:'Explicit isolated TASK-12 mock acceptance fixture approval; no publication'}});
else if(review.final_decision!=='APPROVED')throw new Error('Rejected fixture is not automatically overridden');
await client.action(execution.id,'resume','Own mock fixture QA gate verified and explicitly approved by acceptance harness');
let result;const end=Date.now()+600000;
while(Date.now()<end){result=await client.monitor(execution.id,{timeoutMs:30000});console.log(`Wallpaper resume: ${result.status}`);if(result.status!=='RUNNING')break;}
report.resumed=result;
if(result.status==='COMPLETED'){
 const exported=result.items.find(i=>i.resource_type==='wallpaper_exports');if(!exported)throw new Error('Missing export');
 const archive=(await client.request('/api/v1/wallpaper-exports')).find(e=>e.id===exported.resource_id);
 report.download=await client.downloadExport('wallpaper',archive.id,'storage/data/skills-verification',`${archive.id}.zip`,archive.sha256);
 const qa=await client.plan({skillName:'run-qa',operationId:`smoke:qa:${execution.id}`,projectId:report.projectId,collectionId:report.collectionId,input:{assetIds:[production.master_asset_id],from:new Date(Date.now()-86400000).toISOString(),to:new Date().toISOString()}});
 await client.action(qa.id,'start','Verify completed QA reuse on the new fixture');report.qa=await client.monitor(qa.id);
 if(report.qa.status!=='COMPLETED'||report.qa.items[0].resource_id!==review.id)throw new Error('QA cache reuse did not complete');
 report.outcome='WALLPAPER_EXPORT_AND_QA_REUSE_COMPLETED';
}else{report.outcome='RESUME_PENDING';process.exitCode=1;}
await writeFile(file,JSON.stringify(report,null,2));console.log(report.outcome);
