import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {ProcessingWorkspace,CropEditor} from './ProcessingWorkspace';
afterEach(()=>{cleanup();vi.restoreAllMocks();});
function setup(){
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,options)=>{
  const url=String(input);let data:unknown=[];
  if(url==='/api/assets')data=[{id:'asset-123',width:2000,height:2000,media_type:'image/png'}];
  if(url.endsWith('/processing-profiles'))data=[{id:'p',key:'PREVIEW',version:1,status:'PUBLISHED',definition:{width:1280,height:1280,format:'WEBP',mode:'FIT'}}];
  if(url.endsWith('/processing-worker'))data={status:'ONLINE',device:'cpu',gpuAvailable:false,activeJobs:0};
  if(options?.method==='POST')data={processingRunId:'run',status:'PENDING'};
  return new Response(JSON.stringify(data),{status:200});
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})}><ProcessingWorkspace/></QueryClientProvider>);return fetcher;
}
it('starts an asynchronous run with selected immutable profiles',async()=>{
 const fetcher=setup();const user=userEvent.setup();await screen.findByText('PREVIEW');await user.selectOptions(screen.getByLabelText('Source master'),'asset-123');await user.click(screen.getByRole('button',{name:'Start processing'}));
 await waitFor(()=>expect(fetcher).toHaveBeenCalledWith('/api/v1/assets/asset-123/process',expect.objectContaining({method:'POST',body:JSON.stringify({profiles:['PREVIEW'],manualCrops:{}})})));
 expect(await screen.findByText(/Processing continues in the background/)).toBeInTheDocument();
});
it('shows local worker capability without claiming a GPU',async()=>{setup();await userEvent.click(screen.getByRole('button',{name:'GPU Worker'}));expect(await screen.findByText(/CPU execution/)).toBeInTheDocument();});
it('records manual crop scaling and supports reset',async()=>{
 const onChange=vi.fn();render(<CropEditor source="a" crop={{rectangle:{x:.25,y:0,width:.5,height:1},focalRegions:[],warnings:[]}} value={{x:.25,y:0,width:.5,height:1}} onChange={onChange}/>);
 expect(screen.getByText(/MANUAL/)).toBeInTheDocument();await userEvent.click(screen.getByText('Reset to automatic'));expect(onChange).toHaveBeenCalledWith(undefined);
});
