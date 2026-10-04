import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

export class MediaFactoryClient {
  constructor({baseUrl=process.env.MEDIA_FACTORY_API??'http://localhost:8080',token=process.env.MEDIA_FACTORY_TOKEN,fetchImpl=fetch}={}){
    this.base=new URL(baseUrl);this.token=token;this.fetch=fetchImpl;
    if(this.base.username||this.base.password||this.base.search||this.base.hash)throw new Error('Base URL must not contain credentials, query or fragment');
    if(this.base.protocol!=='https:'&&!(this.base.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(this.base.hostname)))throw new Error('Use HTTPS for a remote API');
  }
  async request(path,{method='GET',body}={}){
    if(!path.startsWith('/api/')||path.includes('..')||path.includes('\\'))throw new Error('Only fixed Media Factory API paths are allowed');
    const url=new URL(path,this.base);if(url.origin!==this.base.origin)throw new Error('Cross-origin request refused');
    const response=await this.fetch(url,{method,redirect:'error',signal:AbortSignal.timeout(30000),headers:{Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),...(this.token?{Authorization:`Bearer ${this.token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
    if(!response.ok)throw new Error(`Media Factory HTTP ${response.status}; inspect execution/domain state before retrying`);
    return response.json();
  }
  async downloadExport(kind,id,root,filename,expectedSha256){
    if(!['stock','wallpaper'].includes(kind)||!/^[a-f0-9]{64}$/i.test(expectedSha256))throw new Error('Export type and authoritative SHA-256 required');
    const target=outputPath(root,filename);
    const response=await this.fetch(new URL(`/api/v1/${kind}-exports/${uuid(id)}/content`,this.base),{redirect:'error',signal:AbortSignal.timeout(120000),headers:this.token?{Authorization:`Bearer ${this.token}`}:{}});
    if(!response.ok)throw new Error(`Media Factory HTTP ${response.status}; export download failed`);
    const bytes=Buffer.from(await response.arrayBuffer());
    if(createHash('sha256').update(bytes).digest('hex')!==expectedSha256.toLowerCase())throw new Error('Export checksum mismatch; no file written');
    await mkdir(resolve(root),{recursive:true});await writeFile(target,bytes,{flag:'wx'});
    return {path:target,sha256:expectedSha256.toLowerCase(),bytes:bytes.length};
  }
  plan(input){return this.request('/api/v1/skills/executions',{method:'POST',body:input});}
  status(id){return this.request(`/api/v1/skills/executions/${uuid(id)}`);}
  action(id,action,reason){if(!['start','resume','approve','cancel'].includes(action))throw new Error('Unsupported action');if(!reason?.trim())throw new Error('Reason required');return this.request(`/api/v1/skills/executions/${uuid(id)}/${action}`,{method:'POST',body:{reason}});}
  async monitor(id,{timeoutMs=120000,sleep=ms=>new Promise(r=>setTimeout(r,ms)),now=()=>Date.now()}={}){
    const deadline=now()+timeoutMs;let delay=2000;
    for(;;){const result=await this.status(id);if(result.status!=='RUNNING'||now()>=deadline)return result;await sleep(Math.min(delay,Math.max(0,deadline-now())));delay=Math.min(15000,delay*1.5);}
  }
}
export function uuid(id){if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id))throw new Error('UUID required');return id;}
export function outputPath(root,filename){if(!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,150}$/.test(filename)||filename.includes('..'))throw new Error('Output filename must be a safe basename');const target=resolve(root,filename);if(dirname(target)!==resolve(root))throw new Error('Output escaped selected directory');return target;}
async function main(){
  const [command,...args]=process.argv.slice(2);const client=new MediaFactoryClient();let result;
  if(command==='plan'){if(args.length!==1)throw new Error('Usage: client.mjs plan request.json');result=await client.plan(JSON.parse(await readFile(args[0],'utf8')));}
  else if(command==='status'||command==='monitor')result=await client[command](args[0]);
  else if(['start','resume','approve','cancel'].includes(command))result=await client.action(args[0],command,args.slice(1).join(' '));
  else if(command==='download-export')result=await client.downloadExport(...args);
  else if(command==='get')result=await client.request(args[0]);
  else throw new Error('Commands: plan <request.json>, status|monitor <UUID>, start|resume|approve|cancel <UUID> <reason>, get <API path>, download-export <stock|wallpaper> <UUID> <directory> <filename> <SHA-256>');
  process.stdout.write(JSON.stringify(result,null,2)+'\n');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href)main().catch(e=>{process.stderr.write(e.message+'\n');process.exitCode=1;});
