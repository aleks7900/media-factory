import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {WallpaperWorkspace} from './WallpaperWorkspace';

afterEach(()=>{cleanup();vi.restoreAllMocks();});
function setup(status='PUBLICATION_REVIEW',failure=false){
 const wallpaper={id:'w1',collection_id:'c1',concept_id:'idea',status,revision:3,profile_key:'ANDROID_AMOLED',metadata:{title:'Moonrise',slug:'moonrise'},thumbnail_id:'thumb',master_asset_id:'asset',amoled_result:{blackPixelRatio:.8,nearBlackPixelRatio:.9,meanLuminance:.05,brightPixelRatio:.03,classification:'AMOLED_SUITABLE',warnings:[]}};
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,options)=>{
  const url=String(input);let data:unknown=[];
  if(url==='/api/projects')data=[{id:'project',name:'Studio'}];
  if(url==='/api/concepts')data=[{id:'idea',name:'Lunar landscape',collection_id:'c1'}];
  if(url.endsWith('/wallpaper-collections'))data=[{id:'c1',name:'Nocturne',amoled:true}];
  if(url.endsWith('/wallpaper-profiles'))data=[{key:'ANDROID_STANDARD',definition:{amoled:false}},{key:'ANDROID_AMOLED',definition:{amoled:true}}];
  if(url.endsWith('/wallpaper-productions'))data=[wallpaper];
  if(url.endsWith('/wallpaper-productions/w1'))data={...wallpaper,variants:[{id:'preview',kind:'ANDROID_PREVIEW',width:540,height:1200,format:'WEBP',validation_status:'VALID'}],packages:[{id:'package1',version:1,manifest:{publicationVersion:1}}],events:[]};
  if(url.endsWith('/production-status'))data={ready:0,generated:1,rejected:0,published:0,attempts:1,active:0,plan:{revision:0,status:'RUNNING'},costs:[]};
  if(options?.method==='POST')return new Response(JSON.stringify(failure?{detail:'Publication eligibility changed'}:{id:'result'}),{status:failure?409:200});
  return new Response(JSON.stringify(data),{status:200});
 });
 const onProcess=vi.fn();
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})}><WallpaperWorkspace onProcess={onProcess}/></QueryClientProvider>);
 return {fetcher,onProcess};
}
it('uses lightweight thumbnails in the wallpaper grid',async()=>{setup();expect(await screen.findByAltText('Moonrise')).toHaveAttribute('src','/api/v1/variants/thumb/content');});
it('shows AMOLED measurements and device variant metadata in review',async()=>{setup();await userEvent.click(await screen.findByRole('button',{name:/Inspect wallpaper/}));expect(await screen.findByText('AMOLED_SUITABLE')).toBeInTheDocument();expect(screen.getByText('80.0%')).toBeInTheDocument();expect(screen.getByText(/540 × 1200/)).toBeInTheDocument();});
it('approves the specific immutable package with the current revision',async()=>{const {fetcher}=setup();await userEvent.click(await screen.findByRole('button',{name:/Inspect wallpaper/}));await userEvent.click(await screen.findByRole('button',{name:'Approve package v1'}));await waitFor(()=>expect(fetcher).toHaveBeenCalledWith('/api/v1/wallpapers/w1/approve-publication',expect.objectContaining({method:'POST',body:JSON.stringify({packageId:'package1',revision:3})})));});
it('reuses the TASK-06 crop editor',async()=>{const {onProcess}=setup();await userEvent.click(await screen.findByRole('button',{name:/Inspect wallpaper/}));await userEvent.click(await screen.findByRole('button',{name:'Open crop editor'}));expect(onProcess).toHaveBeenCalledWith('asset',expect.arrayContaining(['WALLPAPER_MASTER','ANDROID_THUMBNAIL']));});
it('bulk publication targets only the local mock and reports rejection',async()=>{const {fetcher}=setup('APPROVED_FOR_PUBLICATION',true);await userEvent.click(await screen.findByLabelText('Select Moonrise'));await userEvent.click(screen.getByRole('button',{name:'Publish approved to mock'}));expect(await screen.findByRole('status')).toHaveTextContent('1 failed');expect(fetcher).toHaveBeenCalledWith('/api/v1/wallpapers/w1/publish',expect.objectContaining({body:JSON.stringify({target:'MOCK'})}));});
it('starts a bounded collection production plan',async()=>{const {fetcher}=setup();await screen.findByRole('option',{name:'Nocturne · AMOLED'});await userEvent.selectOptions(await screen.findByLabelText('Wallpaper collection'),'c1');await userEvent.click(screen.getByRole('button',{name:'Collections'}));await userEvent.click(await screen.findByRole('button',{name:'Produce collection'}));await waitFor(()=>expect(fetcher).toHaveBeenCalledWith('/api/v1/wallpaper-collections/c1/produce',expect.objectContaining({body:JSON.stringify({targetApproved:10,batchSize:2,maxAttempts:20,maximumCost:0,reservedCostPerAttempt:0,wallpaperProfile:'ANDROID_AMOLED'})})));});



