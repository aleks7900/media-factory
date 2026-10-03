import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {AnalyticsWorkspace,displayMetric,calendarBoundary} from './AnalyticsWorkspace';
afterEach(()=>{cleanup();vi.restoreAllMocks();});
function setup(fail=false,missing=false){
  const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async input=>{
    const path=String(input);
    if(fail&&path.includes('/analytics/'))return new Response(JSON.stringify({detail:'Analytics unavailable'}),{status:503});
    let result:unknown={rows:[{group_key:'ALL',views:missing?null:100,likes:0,downloads:0,revenue:0,cost:2,profit:-2,roi:-1,generated:10,approved:5}],total:30,currency:'USD',warnings:[]};
    if(path.endsWith('/projects')||path.endsWith('/collections'))result=[];
    if(path.includes('/diagnostics'))result={};
    return new Response(JSON.stringify(result));
  });
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><AnalyticsWorkspace/></QueryClientProvider>);
  return fetcher;
}
it('distinguishes missing, zero and negative economics',()=>{
  expect(displayMetric(null,'revenue','USD')).toBe('Unavailable');
  expect(displayMetric(0,'revenue','USD')).toBe('$0.00');
  expect(displayMetric(-2,'profit','USD')).toBe('-$2.00');
  expect(displayMetric(null,'roi','USD')).toBe('Unavailable');
});
it('sends period filters, sorting and pagination to the server',async()=>{
  const f=setup();await screen.findByText('Page 1 · 30 groups');
  await userEvent.selectOptions(screen.getByLabelText('Period'),'Lifetime');
  await userEvent.selectOptions(screen.getByLabelText('Sort by'),'downloads');
  await userEvent.click(screen.getByRole('button',{name:'Next'}));
  await waitFor(()=>expect(f.mock.calls.some(([url])=>String(url).includes('page=1')&&String(url).includes('sort=downloads')&&!String(url).includes('from='))).toBe(true));
});
it('surfaces API failures',async()=>{setup(true);expect((await screen.findAllByRole('alert'))[0]).toHaveTextContent('Analytics unavailable');});
it('converts calendar boundaries through daylight saving transitions',()=>{
  expect(calendarBoundary('2026-03-08','America/New_York')).toBe('2026-03-08T05:00:00.000Z');
  expect(calendarBoundary('2026-03-09','America/New_York')).toBe('2026-03-09T04:00:00.000Z');
  expect(calendarBoundary('2026-10-01','Europe/Bucharest')).toBe('2026-09-30T21:00:00.000Z');
});
it('switches chart metrics without mixing scales',async()=>{setup();await userEvent.selectOptions(screen.getByLabelText('Chart metric'),'revenue');expect(await screen.findByRole('img',{name:'revenue over time'})).toBeInTheDocument();});
it('does not plot unavailable measurements as zero',async()=>{setup(false,true);expect(await screen.findByText('No measurements in this period.')).toBeInTheDocument();expect(screen.queryByRole('img',{name:'views over time'})).toBeNull();});
