import {afterEach, expect, it, vi} from 'vitest';
import {cleanup, render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {PublishingWorkspace} from './PublishingWorkspace';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function setup() {
  const account = {
    id: 'acc-1',
    platform: 'TIKTOK',
    accountId: 'open_id_creator1',
    username: 'mediafactory_cars',
    displayName: 'Media Factory Cars',
    avatarUrl: 'https://avatar.tiktok.com/creator.png',
    scopes: 'user.info.basic,video.publish,video.upload',
    privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'MUTUAL_FOLLOW_FRIENDS', 'SELF_ONLY'],
    commentDisabled: false,
    duetDisabled: false,
    stitchDisabled: false,
    maxVideoPostDurationSec: 600,
    isActive: true,
    isAuthorized: true,
    createdAt: '2026-10-04T10:00:00Z'
  };

  const batch = {
    id: 'batch-1',
    projectId: 'proj-1',
    accountId: 'acc-1',
    accountUsername: 'mediafactory_cars',
    platform: 'TIKTOK',
    name: 'Cars October',
    status: 'RUNNING',
    totalVideos: 100,
    published: 37,
    processing: 2,
    queued: 58,
    failed: 3,
    cancelled: 0,
    draft: 0,
    paused: false,
    isCancelled: false,
    createdAt: '2026-10-04T12:00:00Z',
    completedAt: null
  };

  const tasks = [
    {
      id: 'task-1',
      batchId: 'batch-1',
      platform: 'TIKTOK',
      videoFilename: 'bmw_m4.mp4',
      videoSizeBytes: 15728640,
      durationSeconds: 18.5,
      width: 1080,
      height: 1920,
      caption: 'BMW M4 cinematic edit #bmw #cars',
      privacyLevel: 'PUBLIC_TO_EVERYONE',
      disableComment: false,
      disableDuet: false,
      disableStitch: false,
      status: 'PUBLISHED',
      validationError: null,
      publishId: 'v_pub_123',
      postId: '7350000000000000001',
      postUrl: 'https://www.tiktok.com/@mediafactory_cars/video/7350000000000000001',
      attempts: 1,
      lastErrorCode: null,
      lastErrorMessage: null,
      createdAt: '2026-10-04T12:01:00Z',
      completedAt: '2026-10-04T12:02:00Z'
    },
    {
      id: 'task-2',
      batchId: 'batch-1',
      platform: 'TIKTOK',
      videoFilename: 'audi_rs6.mp4',
      videoSizeBytes: 20971520,
      durationSeconds: 24.0,
      width: 1080,
      height: 1920,
      caption: 'Audi RS6 Avant #audi',
      privacyLevel: 'SELF_ONLY',
      disableComment: false,
      disableDuet: true,
      disableStitch: false,
      status: 'FAILED',
      validationError: null,
      publishId: 'v_pub_124',
      postId: null,
      postUrl: null,
      attempts: 5,
      lastErrorCode: 'TIKTOK_REJECTED',
      lastErrorMessage: 'Transcoding timeout',
      createdAt: '2026-10-04T12:01:00Z',
      completedAt: '2026-10-04T12:05:00Z'
    }
  ];

  const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input);

    if (path.includes('/api/v1/publishing/account')) {
      return new Response(JSON.stringify(account), {status: 200});
    }

    if (path.includes('/api/v1/publishing/batches/batch-1/tasks')) {
      return new Response(JSON.stringify(tasks), {status: 200});
    }

    if (path.endsWith('/api/v1/publishing/batches/batch-1')) {
      return new Response(JSON.stringify(batch), {status: 200});
    }

    if (path.includes('/api/v1/publishing/batches')) {
      return new Response(JSON.stringify([batch]), {status: 200});
    }

    if (path.endsWith('/pause')) {
      return new Response(JSON.stringify({...batch, paused: true, status: 'PAUSED'}), {status: 200});
    }

    if (path.endsWith('/resume')) {
      return new Response(JSON.stringify({...batch, paused: false, status: 'RUNNING'}), {status: 200});
    }

    if (path.endsWith('/retry')) {
      return new Response(JSON.stringify({...batch, failed: 0, queued: 61}), {status: 200});
    }

    if (path.endsWith('/cancel')) {
      return new Response(JSON.stringify({...batch, isCancelled: true, status: 'CANCELLED'}), {status: 200});
    }

    return new Response(JSON.stringify({}), {status: 200});
  });

  const client = new QueryClient({
    defaultOptions: {queries: {retry: false}}
  });

  const result = render(
    <QueryClientProvider client={client}>
      <PublishingWorkspace />
    </QueryClientProvider>
  );

  return {...result, fetcher};
}

it('renders TikTok account banner, handle, and Direct Post API badge', async () => {
  setup();

  await waitFor(() => {
    expect(screen.getByText('@mediafactory_cars')).toBeInTheDocument();
  });

  expect(screen.getByText('Direct Post API v2')).toBeInTheDocument();
  expect(screen.getByText(/Media Factory Cars/)).toBeInTheDocument();
});

it('renders batch metrics breakdown matching specifications: Published 37, Processing 2, Queued 58, Failed 3', async () => {
  setup();

  await waitFor(() => {
    expect(screen.getByText('Batch: Cars October')).toBeInTheDocument();
  });

  expect(screen.getByText('100')).toBeInTheDocument(); // total
  expect(screen.getByText('37')).toBeInTheDocument();  // published
  expect(screen.getByText('2')).toBeInTheDocument();   // processing
  expect(screen.getByText('58')).toBeInTheDocument();  // queued
  expect(screen.getByText('3')).toBeInTheDocument();   // failed
});

it('renders batch action controls [Pause], [Resume], [Retry Failed], [Cancel] and executes actions', async () => {
  const {fetcher} = setup();

  await waitFor(() => {
    expect(screen.getByRole('button', {name: /pause/i})).toBeInTheDocument();
  });

  const pauseBtn = screen.getByRole('button', {name: /pause/i});
  const resumeBtn = screen.getByRole('button', {name: /resume/i});
  const retryBtn = screen.getByRole('button', {name: /retry failed/i});
  const cancelBtn = screen.getByRole('button', {name: /cancel/i});

  expect(pauseBtn).toBeInTheDocument();
  expect(resumeBtn).toBeInTheDocument();
  expect(retryBtn).toBeInTheDocument();
  expect(cancelBtn).toBeInTheDocument();

  // Click Pause
  await userEvent.click(pauseBtn);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining('/api/v1/publishing/batches/batch-1/pause'),
    expect.anything()
  );

  // Click Retry Failed
  await userEvent.click(retryBtn);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining('/api/v1/publishing/batches/batch-1/retry'),
    expect.anything()
  );
});

it('renders video tasks with thumbnails, filenames, captions, status, and TikTok post links', async () => {
  setup();

  await waitFor(() => {
    expect(screen.getByText('bmw_m4.mp4')).toBeInTheDocument();
  });

  expect(screen.getByText('BMW M4 cinematic edit #bmw #cars')).toBeInTheDocument();
  expect(screen.getAllByText('PUBLISHED').length).toBeGreaterThanOrEqual(2);
  expect(screen.getAllByText('FAILED').length).toBeGreaterThanOrEqual(2);
  expect(screen.getByText(/Transcoding timeout/)).toBeInTheDocument();

  const postLink = screen.getByRole('link', {name: /TikTok Post/i});
  expect(postLink).toHaveAttribute('href', 'https://www.tiktok.com/@mediafactory_cars/video/7350000000000000001');
});
