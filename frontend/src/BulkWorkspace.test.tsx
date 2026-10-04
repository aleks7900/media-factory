import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {BulkWorkspace} from './BulkWorkspace';
afterEach(()=>{cleanup();vi.restoreAllMocks();});
function setup(failure=false,kind:'GPT_IMAGE'|'GEMINI_VIDEO'='GPT_IMAGE') {
  const batch={id:'batch',name:'Cars batch',provider:'mock',model:'studio-mock-v1',archive_name:'cars.zip',totalTasks:126,references:2,progress:50,status:'RUNNING',counts:{COMPLETED:63,GENERATING:3,QUEUED:59,FAILED:1},created_at:'2026-10-04T10:00:00Z'};
  const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,init)=>{
    const path=String(input);if(failure&&path.includes('/capabilities'))return new Response(JSON.stringify({detail:'Capabilities unavailable'}),{status:503});
    let data:unknown=[];
    if(path.endsWith('/projects'))data=[{id:'project',name:'Studio'}];
    if(path.endsWith('/capabilities'))data={imageProviders:[{provider:'mock',enabled:true,models:['studio-mock-v1'],defaultModel:'studio-mock-v1'},{provider:'openai',enabled:true,models:['configured-model'],defaultModel:'configured-model'}],videoProviders:[{provider:'mock-video',enabled:true,model:'deterministic-motion-v1',capabilities:{models:['deterministic-motion-v1'],resolutions:['1280:720'],durations:[2,5,10]}},{provider:'gemini',enabled:false,model:'veo-model',capabilities:{models:['veo-model'],resolutions:['1280:720'],durations:[4,6,8]}}],archiveLimits:{archiveBytes:104857600}};
    if(path.includes('/batches?'))data=[batch];
    if(path.endsWith('/batches/batch'))data=batch;
    if(path.includes('/batches/batch/tasks?'))data=[{id:'task',name:'BMW',status:'FAILED',inputs:[{name:'front.png'}],retry_count:2,error_code:'PROVIDER_OUTCOME_UNKNOWN'}];
    if(path.endsWith('/tasks/task'))data={id:'task',name:'BMW',provider:'mock',model:'studio-mock-v1',status:'FAILED',prompt:'A BMW on a winding road',inputs:[{name:'front.png'}],retry_count:2,attempts:1,outcome_unknown:true,error_message:'Submission may have succeeded',provider_metadata:{},attemptHistory:[]};
    if(init?.method==='POST')data=batch;
    return new Response(JSON.stringify(data));
  });
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><BulkWorkspace kind={kind}/></QueryClientProvider>);return fetcher;
}
it('imports multipart ZIP with stable idempotency and shows validation summary',async()=>{
  const fetcher=setup();await screen.findByRole('option',{name:'Studio'});await userEvent.selectOptions(screen.getByLabelText('Project'),'project');
  await userEvent.upload(screen.getByLabelText('Task ZIP archive'),new File(['zip fixture'],'tasks.zip',{type:'application/zip'}));
  await userEvent.click(screen.getByRole('button',{name:'Import & queue tasks'}));
  expect(await screen.findByRole('status')).toHaveTextContent('126 tasks: 125 valid, 1 invalid, 2 references');
  const first=fetcher.mock.calls.find(([url,init])=>String(url).endsWith('/batches')&&init?.method==='POST');
  expect(first?.[1]?.body).toBeInstanceOf(FormData);expect((first?.[1]?.headers as Record<string,string>)['Idempotency-Key']).toBeTruthy();
  await userEvent.click(screen.getByRole('button',{name:'Import & queue tasks'}));
  const calls=fetcher.mock.calls.filter(([url,init])=>String(url).endsWith('/batches')&&init?.method==='POST');
  expect(calls[1][1]?.headers).toEqual(calls[0][1]?.headers);
});
it('requires paid authorization and resets it when settings change',async()=>{
  setup();await screen.findByRole('option',{name:'Studio'});await userEvent.selectOptions(screen.getByLabelText('Project'),'project');await userEvent.selectOptions(screen.getByLabelText('Provider'),'openai');
  await userEvent.upload(screen.getByLabelText('Task ZIP archive'),new File(['zip'],'tasks.zip',{type:'application/zip'}));
  const submit=screen.getByRole('button',{name:'Import & queue tasks'});expect(submit).toBeDisabled();
  await userEvent.click(screen.getByRole('checkbox',{name:/I authorize paid generation/}));expect(submit).toBeEnabled();
  await userEvent.selectOptions(screen.getByLabelText('Quality'),'HIGH');expect(submit).toBeDisabled();
});
it('shows original prompt and references while blocking ambiguous retries',async()=>{
  setup();await screen.findByRole('option',{name:'Studio'});await userEvent.selectOptions(screen.getByLabelText('Project'),'project');await userEvent.click(await screen.findByRole('button',{name:/Cars batch/}));await userEvent.click(await screen.findByRole('button',{name:'BMW'}));
  expect(await screen.findByText('A BMW on a winding road')).toBeInTheDocument();expect(screen.getByAltText('front.png')).toHaveAttribute('src','/api/v1/bulk/tasks/task/references/0');
  expect(screen.getByRole('button',{name:/^retry$/})).toBeDisabled();expect(screen.getByRole('button',{name:'regenerate'})).toBeDisabled();
  expect(screen.getByRole('link',{name:'Results ZIP'})).toHaveAttribute('href','/api/v1/bulk/batches/batch/results.zip');
});
it('filters batch history on the server and displays unconfigured video providers',async()=>{
  const fetcher=setup(false,'GEMINI_VIDEO');await screen.findByRole('option',{name:'Studio'});await userEvent.selectOptions(screen.getByLabelText('Project'),'project');
  expect(screen.getByRole('option',{name:'gemini · not configured'})).toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText('Batch status'),'FAILED');await waitFor(()=>expect(fetcher.mock.calls.some(([url])=>String(url).includes('status=FAILED'))).toBe(true));
});
it('surfaces unavailable capabilities',async()=>{setup(true);expect(await screen.findByRole('alert')).toHaveTextContent('Capabilities unavailable');});
