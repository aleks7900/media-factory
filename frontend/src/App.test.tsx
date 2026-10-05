import {afterEach, describe, expect, it, vi} from 'vitest';
import {cleanup, render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {App} from './App';
import {setAuth, clearAuth} from './api';

afterEach(() => {
  cleanup();
  clearAuth();
  vi.restoreAllMocks();
});
const qaReview = {
  id: 'review-1',
  asset_id: 'asset-12345',
  generation_id: 'gen-1',
  revision: 1,
  execution_status: 'COMPLETED',
  automatic_decision: 'NEEDS_REVIEW',
  final_decision: 'NEEDS_REVIEW',
  collection_name: 'Studio',
  width: 1024,
  height: 1024,
  highest_severity: null,
  prompt_compliance: 1,
  findings: [],
  dimensions: [],
  actions: [],
  history: [],
  attempts: [],
  asset: {
    width: 1024,
    height: 1024,
    size_bytes: 1200,
    sha256: 'abc',
    current_review_id: 'review-1'
  },
  context_snapshot: {},
  rules_triggered: [],
  costs: []
};

function setup() {
  setAuth('test-token', { username: 'admin', role: 'ROLE_ADMIN' });
  const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    const path = String(input);
    let data: unknown = [];
    if (path === '/api/auth/me') data = { username: 'admin', role: 'ROLE_ADMIN' };
    if (path === '/api/dashboard') data = {
      generated_today: 1,
      approved_today: 0,
      pending_review: 1
    };
    if (path === '/api/v1/qa/dashboard') data = {
      pending_qa: 0,
      needs_human_review: 1,
      approved_today: 0,
      rejected_today: 0,
      auto_approval_rate: 0,
      human_override_rate: 0,
      average_qa_seconds: 1,
      qa_cost_today: 0
    };
    if (path.startsWith('/api/v1/reviews/queue')) data = {items: [qaReview], total: 1};
    if (path === '/api/v1/reviews/review-1') data = qaReview;
    if (path === '/api/assets') data = [{
      id: 'asset-12345',
      generation_id: 'gen-1',
      width: 1024,
      height: 1024
    }];
    if (path === '/api/generations') data = [{id: 'gen-1', status: 'QA_PENDING'}];
    if (options?.method === 'POST') data = {id: 'review-1'};
    return new Response(JSON.stringify(data), {
      status: 200,
      headers: {'Content-Type': 'application/json'}
    });
  });
  render(<QueryClientProvider
      client={new QueryClient({defaultOptions: {queries: {retry: false}}})}><App/></QueryClientProvider>);
  return fetcher;
}

describe('Media Factory dashboard', () => {
  it('renders real dashboard metrics and navigates to settings', async () => {
    setup();
    expect(await screen.findByText('Studio frame asset-')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', {name: 'Settings'}));
    expect(screen.getByText('Immutable originals + SHA-256 checksums')).toBeInTheDocument();
  });
  it('approves an asset through the review API', async () => {
    const fetcher = setup();
    await userEvent.click(screen.getByRole('button', {name: /^Review/}));
    await userEvent.click(await screen.findByRole('button', {name: 'Inspect evidence'}));
    await userEvent.click(await screen.findByRole('button', {name: 'Approve'}));
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/v1/reviews/review-1/approve', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({revision: 1, reasonCode: null, reasonText: ''})
    })));
  });
  it('regenerates with an idempotency key', async () => {
    const fetcher = setup();
    await userEvent.click(screen.getByRole('button', {name: /^Review/}));
    await userEvent.click(await screen.findByRole('button', {name: 'Inspect evidence'}));
    await userEvent.click(await screen.findByRole('button', {name: 'Regenerate'}));
    await userEvent.click(screen.getByRole('button', {name: 'Create regeneration'}));
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/v1/reviews/review-1/regenerate', expect.objectContaining({headers: expect.objectContaining({'Idempotency-Key': expect.any(String)})})));
  });
  it('explains prerequisites before creating a generation', async () => {
    setup();
    await userEvent.click(screen.getByRole('button', {name: 'New generation'}));
    expect(screen.getByRole('dialog')).toHaveTextContent('Create a project, collection, and concept');
  });

  it('renders login screen when unauthenticated and logs in on submit', async () => {
    clearAuth();
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input);
      if (path === '/api/auth/me') {
        return new Response(JSON.stringify({ error: 'Unauthorized' }), { status: 401 });
      }
      if (path === '/api/auth/login') {
        return new Response(JSON.stringify({ token: 'jwt-new', username: 'admin', role: 'ROLE_ADMIN' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      if (path === '/api/dashboard') {
        return new Response(JSON.stringify({ generated_today: 0, approved_today: 0, pending_review: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      return new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });

    render(
      <QueryClientProvider client={new QueryClient({defaultOptions: {queries: {retry: false}}})}>
        <App />
      </QueryClientProvider>
    );

    expect(await screen.findByRole('button', { name: /Sign In to Studio/i })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Username or Email/i), 'admin');
    await userEvent.type(screen.getByLabelText(/^Password/i), 'admin123');
    await userEvent.click(screen.getByRole('button', { name: /Sign In to Studio/i }));

    expect(await screen.findByText(/Studio overview/i)).toBeInTheDocument();
  });

  it('logging out removes auth state and returns to login screen', async () => {
    setup();
    expect(await screen.findByText('Studio frame asset-')).toBeInTheDocument();

    const logoutBtns = screen.getAllByRole('button', { name: /log out/i });
    await userEvent.click(logoutBtns[0]);

    expect(await screen.findByRole('button', { name: /Sign In to Studio/i })).toBeInTheDocument();
  });

  it('exchanges OAuth authorization code exactly once and clears URL query', async () => {
    const replaceStateSpy = vi.spyOn(window.history, 'replaceState');
    const origSearch = window.location.search;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        search: '?code=auth_code_999&state=state_abc',
        pathname: '/publishing/tiktok/callback',
        href: 'http://localhost:3000/publishing/tiktok/callback?code=auth_code_999&state=state_abc'
      }
    });

    const fetcher = setup();

    await waitFor(() => {
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining('/v1/publishing/tiktok/callback'),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ code: 'auth_code_999', state: 'state_abc' })
        })
      );
    });

    // Verify it was called only once (no duplicate code exchange)
    const callbackCalls = fetcher.mock.calls.filter(call =>
      String(call[0]).includes('/v1/publishing/tiktok/callback')
    );
    expect(callbackCalls.length).toBe(1);

    // Verify URL was cleaned
    expect(replaceStateSpy).toHaveBeenCalled();
  });
});

