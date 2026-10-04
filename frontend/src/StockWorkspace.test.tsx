import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render, screen, waitFor, within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {StockWorkspace} from './StockWorkspace';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function setup() {
  const data = {
    title: 'Blue abstract texture',
    description: 'An abstract texture in blue tones.',
    keywords: [{value: 'blue', rank: 1, source: 'LLM', confidence: .8}, {
      value: 'abstract texture',
      rank: 2,
      source: 'LLM',
      confidence: .7
    }],
    categories: ['ABSTRACT'],
    contentType: 'UNDETERMINED',
    aiGenerated: true
  };
  const production = {
    id: 's1',
    collection_id: 'c1',
    concept_id: 'idea',
    revision: 4,
    status: 'METADATA_REVIEW',
    thumbnail_id: 'thumb',
    metadata: data
  };
  const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, opts) => {
    const path = String(input);
    let result: unknown = [];
    if (path.endsWith('/stock-productions')) result = [production];
    if (path.endsWith('/stock-productions/s1')) result = {
      ...production,
      variant: {id: 'master', width: 2048, height: 2048, format: 'JPEG', size_bytes: 250000},
      qa: {final_decision: 'APPROVED'},
      metadataVersions: [{
        id: 'm1',
        version: 1,
        source: 'GENERATED',
        lifecycle: 'GENERATED',
        data,
        validation: {
          status: 'WARNING',
          issues: [{
            field: 'metadata',
            code: 'MOCK_OBSERVATIONS_REQUIRE_HUMAN_REVIEW',
            severity: 'WARNING',
            value: 'Review fixture'
          }]
        }
      }],
      technical: [{result: {valid: true, checks: []}}]
    };
    if (path.endsWith('/stock-profiles')) result = [{
      id: 'profile',
      profile_key: 'STOCK_GENERIC',
      version: 1,
      definition: {enabled: true}
    }];
    if (path.endsWith('/stock-export-profiles')) result = [{
      id: 'exportProfile',
      profile_key: 'GENERIC_CSV',
      version: 1,
      definition: {}
    }];
    if (path.endsWith('/stock-dashboard')) result = {candidates: 1, metadata_review: 1};
    if (path.endsWith('/stock-collections')) result = [{id: 'c1', name: 'Abstract library'}];
    if (opts?.method === 'POST' || opts?.method === 'PUT') result = {id: 'saved'};
    return new Response(JSON.stringify(result), {status: 200});
  });
  render(<QueryClientProvider client={new QueryClient({defaultOptions: {queries: {retry: false}}})}><StockWorkspace/></QueryClientProvider>);
  return fetcher;
}

it('shows lightweight thumbnails and candidate metadata', async () => {
  setup();
  expect(await screen.findByAltText('Blue abstract texture')).toHaveAttribute('src', '/api/v1/variants/thumb/content');
  expect(screen.getByText('2 ranked keywords · AI generated')).toBeInTheDocument();
});
it('shows stock dimensions, QA and metadata warnings', async () => {
  setup();
  await userEvent.click(await screen.findByRole('button', {name: /Review stock/}));
  const d = await screen.findByRole('dialog', {name: 'Stock review'});
  expect(within(d).getByText(/4.194 MP/)).toBeInTheDocument();
  expect(within(d).getByText('APPROVED')).toBeInTheDocument();
  expect(within(d).getByText(/MOCK_OBSERVATIONS/)).toBeInTheDocument();
});
it('approves with revision and explicit warning acknowledgement', async () => {
  const f = setup();
  await userEvent.click(await screen.findByRole('button', {name: /Review stock/}));
  await userEvent.click(await screen.findByLabelText('I reviewed the image and metadata warnings'));
  await userEvent.click(screen.getByRole('button', {name: 'Approve for export'}));
  await waitFor(() => expect(f).toHaveBeenCalledWith('/api/v1/stock-productions/s1/approve', expect.objectContaining({
    body: JSON.stringify({
      revision: 4,
      acknowledgeWarnings: true
    })
  })));
});
it('reorders keywords and saves a new metadata version', async () => {
  const f = setup();
  await userEvent.click(await screen.findByRole('button', {name: /Review stock/}));
  await userEvent.click(await screen.findByRole('button', {name: 'Edit metadata'}));
  await userEvent.click(screen.getByLabelText('Move keyword 2 up'));
  expect(screen.getByLabelText('Keyword 1')).toHaveValue('abstract texture');
  await userEvent.click(screen.getByRole('button', {name: 'Save as new version'}));
  await waitFor(() => expect(f.mock.calls.some(([url, o]) => String(url).endsWith('/metadata') && o?.method === 'PUT' && JSON.parse(o.body as string).data.keywords[0].value === 'abstract texture')).toBe(true));
});
it('queues selected export with explicit policy', async () => {
  const f = setup();
  await userEvent.click(await screen.findByLabelText('Select Blue abstract texture'));
  await userEvent.selectOptions(screen.getByLabelText('Export policy'), 'VALID_ONLY');
  await userEvent.click(screen.getByRole('button', {name: 'Export selected'}));
  await waitFor(() => expect(f).toHaveBeenCalledWith('/api/v1/stock-exports', expect.objectContaining({
    body: JSON.stringify({
      profile: 'GENERIC_CSV',
      stockProductionIds: ['s1'],
      incremental: false,
      policy: 'VALID_ONLY'
    })
  })));
});
it('requests a targeted metadata regeneration', async () => {
  const f = setup();
  await userEvent.click(await screen.findByRole('button', {name: /Review stock/}));
  await userEvent.click(await screen.findByRole('button', {name: 'Regenerate title'}));
  await waitFor(() => expect(f).toHaveBeenCalledWith('/api/v1/stock-productions/s1/metadata/regenerate', expect.objectContaining({
    body: JSON.stringify({
      revision: 4,
      scope: 'TITLE'
    })
  })));
});
