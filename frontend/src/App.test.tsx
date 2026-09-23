import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
afterEach(cleanup);
function setup() {
 const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input,options) => {
  const path=String(input);let data:unknown=[];
  if(path==='/api/dashboard') data={generated_today:1,approved_today:0,pending_review:1};
  if(path==='/api/assets') data=[{id:'asset-12345',generation_id:'gen-1',width:1024,height:1024}];
  if(path==='/api/generations') data=[{id:'gen-1',status:'QA_PENDING'}];
  if(options?.method==='POST') data={id:'review-1'};
  return new Response(JSON.stringify(data),{status:200,headers:{'Content-Type':'application/json'}});
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><App/></QueryClientProvider>);
 return fetcher;
}
describe('Media Factory dashboard',()=>{
 it('renders real dashboard metrics and navigates to settings',async()=>{setup();expect(await screen.findByText('Studio frame asset-')).toBeInTheDocument();await userEvent.click(screen.getByRole('button',{name:'Settings'}));expect(screen.getByText('Immutable originals + SHA-256 checksums')).toBeInTheDocument();});
 it('approves an asset through the review API',async()=>{const fetcher=setup();await userEvent.click(screen.getByRole('button',{name:/^Review/}));await userEvent.click(await screen.findByRole('button',{name:'Approve'}));await waitFor(()=>expect(fetcher).toHaveBeenCalledWith('/api/reviews',expect.objectContaining({method:'POST',body:JSON.stringify({assetId:'asset-12345',decision:'APPROVED',reason:''})})));});
 it('regenerates with an idempotency key',async()=>{const fetcher=setup();await userEvent.click(screen.getByRole('button',{name:/^Review/}));await userEvent.click(await screen.findByRole('button',{name:'Regenerate'}));await waitFor(()=>expect(fetcher).toHaveBeenCalledWith('/api/assets/asset-12345/regenerate',expect.objectContaining({headers:expect.objectContaining({'Idempotency-Key':expect.any(String)})})));});
 it('explains prerequisites before creating a generation',async()=>{setup();await userEvent.click(screen.getByRole('button',{name:'New generation'}));expect(screen.getByRole('dialog')).toHaveTextContent('Create a project, collection, and concept');});
});
