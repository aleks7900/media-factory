import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {FeedbackWorkspace,feedbackNumber} from './FeedbackWorkspace';
afterEach(()=>{cleanup();vi.restoreAllMocks();});
function setup(fail=false){
 const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,init)=>{
  const path=String(input);if(fail&&path.includes('/feedback/'))return new Response(JSON.stringify({detail:'Feedback unavailable'}),{status:503});
  let result:unknown=[];
  if(path.endsWith('/overview'))result={new_findings:3,stale_findings:1};
  if(path.endsWith('/collections'))result=[{id:'collection',name:'Wolves'}];
  if(path.includes('/attributes'))result=[{id:'attribute',key:'brightness',name:'Brightness',value_type:'NUMBER'}];
  if(path.includes('/findings'))result=path.endsWith('/finding')?{id:'finding',attribute_key:'dark_background',target_metric:'DOWNLOADS',attribute_value:true,confidence:'LOW',statistics:{control:{count:20,mean:10,median:9,p25:8,p75:12,p90:15},treatment:{count:20,mean:30,median:29,p25:28,p75:32,p90:35},absoluteDifference:20,confidenceInterval:[18,22]},warnings:['PROVIDER_IMBALANCE'],evidence:[{asset_id:'asset',role:'POSITIVE_EXAMPLE',value:30,thumbnail_variant_id:'thumbnail'}]}:[{id:'finding',attribute_key:'dark_background',target_metric:'DOWNLOADS',confidence:'LOW',evidence_status:'EXPLORATORY',statistics:{absoluteDifference:20,treatment:{count:20},control:{count:20}}}];
  if(path.includes('/hypotheses'))result=path.endsWith('/hypothesis')?{id:'hypothesis',title:'Test dark backgrounds',description:'Candidate test',rationale:'Observed association',status:'PROPOSED',finding_id:'finding'}:[{id:'hypothesis',title:'Test dark backgrounds',status:'PROPOSED'}];
  if(path.includes('/experiments'))result=path.endsWith('/experiment')?{id:'experiment',name:'Background A/B',status:'DRAFT',plan:{definition:{controlVersionId:'control',treatmentVersionId:'treatment',change:{background:'black'}},primary_metric:'DOWNLOADS',target_sample:50,observation_days:30,max_budget:5,estimated_cost:0,currency:'USD',approved_at:null},funnel:[{id:'A',key:'A',assigned:0}],costs:[],timeline:[]}:[{id:'experiment',name:'Background A/B',max_budget:5,estimated_cost:0,target_sample:50,observation_days:30,currency:'USD'}];
  if(path.includes('/learnings'))result=path.endsWith('/learning')?{id:'learning',summary:'Observed result',status:'CONTRADICTED',evidence_status:'CONFLICTING_EVIDENCE',experiment_id:'experiment',hypothesis_id:'hypothesis'}:[{id:'learning',summary:'Observed result',status:'CONTRADICTED'}];
  if(init?.method==='POST')result={status:'QUEUED'};
  return new Response(JSON.stringify(result));
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><FeedbackWorkspace/></QueryClientProvider>);return fetcher;
}
it('shows the controlled loop and missing-data distinction',async()=>{setup();expect(await screen.findByText('new findings')).toBeInTheDocument();expect(feedbackNumber(null)).toBe('Unavailable');expect(feedbackNumber(0)).toBe('0');expect(screen.getByText(/Nothing here automatically changes/)).toBeInTheDocument();});
it('queues an explicitly scoped analysis',async()=>{const fetcher=setup();await screen.findByRole('option',{name:'Wolves'});await userEvent.selectOptions(await screen.findByLabelText('Feedback collection'),'collection');await userEvent.selectOptions(screen.getByLabelText('Feedback metric'),'REVENUE');await userEvent.click(screen.getByRole('button',{name:'Analyze patterns'}));await waitFor(()=>expect(fetcher.mock.calls.some(([url,init])=>String(url).endsWith('/feedback/analyses')&&JSON.parse(String(init?.body)).metric==='REVENUE')).toBe(true));});
it('explores distributions, warnings and visual evidence',async()=>{setup();await userEvent.click(screen.getByRole('button',{name:'Findings'}));await userEvent.click(await screen.findByRole('button',{name:'Review details'}));expect(await screen.findByText('Comparison group')).toBeInTheDocument();expect(screen.getByText('PROVIDER_IMBALANCE')).toBeInTheDocument();expect(screen.getByAltText('POSITIVE_EXAMPLE asset')).toBeInTheDocument();await userEvent.type(screen.getByLabelText('Filter feedback records'),'no match');expect(screen.getByText(/No records yet/)).toBeInTheDocument();});
it('requires review attribution before approving a hypothesis',async()=>{const fetcher=setup();await userEvent.click(screen.getByRole('button',{name:'Hypotheses'}));await userEvent.click(await screen.findByRole('button',{name:'Review details'}));const approve=await screen.findByRole('button',{name:'Approve hypothesis'});expect(approve).toBeDisabled();await userEvent.type(screen.getByLabelText('Feedback review reason'),'Evidence reviewed');await userEvent.click(approve);await waitFor(()=>expect(fetcher.mock.calls.some(([url])=>String(url).endsWith('/hypotheses/hypothesis/approve'))).toBe(true));});
it('shows budget, registered plan and funnel while blocking unapproved generation',async()=>{setup();await userEvent.click(screen.getByRole('button',{name:'Experiment Proposals'}));await userEvent.click(await screen.findByRole('button',{name:'Review details'}));expect(await screen.findByText('Variant production funnel')).toBeInTheDocument();expect(screen.getByRole('button',{name:'Queue next 25 generations'})).toBeDisabled();expect(screen.getByText(/Maximum budget:/)).toBeInTheDocument();});
it('preserves contradictory learning history and traceability',async()=>{setup();await userEvent.click(screen.getByRole('button',{name:'Learnings'}));await userEvent.click(await screen.findByRole('button',{name:'Review details'}));expect((await screen.findAllByText(/CONFLICTING_EVIDENCE/))[0]).toBeInTheDocument();expect(screen.getByRole('button',{name:'Open experiment'})).toBeInTheDocument();});
it('surfaces failed API requests',async()=>{setup(true);expect((await screen.findAllByRole('alert'))[0]).toHaveTextContent('Feedback unavailable');});


