import { chromium, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';

// Read-only browser verification. Seed free sample data with scripts/qa-smoke.ps1 first.
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1536, height: 1024 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
try {
 await page.goto(process.env.MEDIA_FACTORY_URL ?? 'http://localhost:3000', { waitUntil: 'networkidle' });
 await expect(page.getByRole('heading', { name: 'Studio overview' })).toBeVisible();
 await expect(page.getByRole('region', { name: 'Quality assurance metrics' })).toBeVisible();
 await page.getByRole('button', { name: /^Review/ }).click();
 await expect(page.getByRole('heading', { name: 'Every frame, considered.' })).toBeVisible();
 await expect(page.getByRole('button', { name: 'Inspect evidence' }).first()).toBeVisible();
 await mkdir('test-results', { recursive: true });
 await page.screenshot({ path: 'test-results/qa-grid.png', fullPage: true });
 await page.getByRole('button', { name: 'Inspect evidence' }).first().click();
 const dialog = page.getByRole('dialog', { name: 'Asset quality review' });
 await expect(dialog).toBeVisible();
 await expect(dialog.getByText('Automated decision', { exact: true })).toBeVisible();
 await expect(dialog.getByText('Final decision', { exact: true })).toBeVisible();
 await expect(dialog.getByRole('heading', { name: 'Quality dimensions' })).toBeVisible();
 await expect(dialog.getByRole('img', { name: 'Full resolution generated asset' })).toBeVisible();
 await dialog.getByRole('button', { name: '100%', exact: true }).click();
 await expect(dialog.getByText('100% · drag to pan')).toBeVisible();
 await dialog.getByRole('button', { name: 'Fit', exact: true }).click();
 await dialog.getByText('Exact prompt comparison', { exact: true }).click();
 await expect(dialog.getByRole('heading', { name: 'Canonical positive', exact: true })).toBeVisible();
 await dialog.screenshot({ path: 'test-results/qa-detail.png' });
 await page.keyboard.press('Escape');
 await expect(dialog).not.toBeVisible();
 await page.setViewportSize({ width: 1100, height: 850 });
 await expect(page.getByRole('heading', { name: 'Every frame, considered.' })).toBeVisible();
 expect(errors).toEqual([]);
 console.log('PASS: deployed dashboard metrics, review grid, evidence dialog, full-resolution zoom, prompt comparison, Escape, responsive viewport and zero browser runtime errors.');
} finally { await browser.close(); }
