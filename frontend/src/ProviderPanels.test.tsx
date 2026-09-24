import {afterEach, describe, expect, it, vi} from 'vitest';
import {cleanup, render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {GenerationDetails, GenerationDialog, type ProviderInfo, ProvidersPanel} from './ProviderPanels';

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});
const provider: ProviderInfo = {
    id: 'openai',
    name: 'OpenAI',
    enabled: true,
    health: 'HEALTHY',
    defaultModel: 'gpt-image-2',
    models: ['gpt-image-2'],
    default: true,
    environment: 'development',
    capabilities: {
        supportedFormats: ['PNG', 'JPEG'],
        supportedQualities: ['AUTO', 'LOW'],
        supportedSizes: ['1024x1024'],
        arbitraryDimensions: false,
        supportsTransparentBackground: true
    },
    requestsPerMinute: 20,
    maxConcurrent: 2,
    stats: {
        requests_today: 3,
        success_rate: 66.67,
        average_latency_ms: 1200,
        estimated_cost_today: null,
        unknown_cost_attempts: 1
    }
};

function wrap(element: React.ReactNode) {
    return render(<QueryClientProvider
        client={new QueryClient({defaultOptions: {queries: {retry: false}}})}>{element}</QueryClientProvider>);
}

describe('Provider operations UI', () => {
    it('shows safe health, limits and unknown costs', () => {
        wrap(<ProvidersPanel providers={[provider]}/>);
        expect(screen.getByText('HEALTHY')).toBeInTheDocument();
        expect(screen.getByText('Unknown')).toBeInTheDocument();
        expect(screen.getByText('2 concurrent')).toBeInTheDocument();
    });
    it('submits auto routing and explicit quality asynchronously', async () => {
        const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async url => new Response(JSON.stringify(String(url).includes('/generations/images') ? {generationId: 'gen-1'} : []), {status: String(url).includes('/generations/images') ? 202 : 200}));
        const created = vi.fn();
        wrap(<GenerationDialog concepts={[{id: 'concept-1', name: 'Concept'}]} providers={[provider]} close={() => {
        }} onCreated={created}/>);
        await userEvent.type(screen.getByLabelText('Creative prompt'), 'A quiet studio');
        await userEvent.selectOptions(screen.getByLabelText('Quality'), 'LOW');
        await userEvent.selectOptions(screen.getByLabelText('Aspect ratio'), 'PORTRAIT');
        await userEvent.click(screen.getByRole('button', {name: 'Generate image'}));
        await waitFor(() => expect(created).toHaveBeenCalledWith('gen-1'));
        const options = fetcher.mock.calls.find(c => String(c[0]).includes('/generations/images'))![1]!;
        expect(JSON.parse(String(options.body))).toMatchObject({
            provider: null,
            quality: 'LOW',
            aspectRatio: 'PORTRAIT'
        });
        expect(options.headers).toHaveProperty('Idempotency-Key');
    });
    it('shows fallback attempt history and requires acknowledgement of duplicate billing', async () => {
        vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
            id: 'gen-1',
            status: 'FAILED',
            selected_provider: 'openai',
            final_provider: null,
            model: 'gpt-image-2',
            provider_route: [{provider: 'openai', model: 'gpt-image-2'}, {provider: 'mock', model: 'studio-mock-v1'}],
            attempts: [{
                id: 'a-1',
                provider: 'openai',
                model: 'gpt-image-2',
                attempt_number: 1,
                status: 'TIMED_OUT',
                duration_ms: 120000,
                fallback: false,
                error_type: 'TIMEOUT',
                error_message: 'Request timed out',
                outcome_unknown: true,
                estimated_cost: null,
                currency: 'USD'
            }],
            assets: [],
            job: {id: 'job-1', status: 'FAILED', recovery_required: true, failure_reason: 'Provider outcome unknown'},
            costs: [{currency: 'USD', estimated_total: null, actual_total: null, unknown_attempts: 1}]
        }), {status: 200}));
        wrap(<GenerationDetails id="gen-1" close={() => {
        }}/>);
        expect(await screen.findByText('TIMED_OUT')).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Retry generation'})).toBeDisabled();
        await userEvent.click(screen.getByRole('checkbox'));
        expect(screen.getByRole('button', {name: 'Retry generation'})).toBeEnabled();
        expect(screen.getByText(/Cost unknown/)).toBeInTheDocument();
    });
});
