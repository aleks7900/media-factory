import {chromium, expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

// Read-only UI checks using the explicitly mock-only seed from scripts/smoke-bulk.py.
const state=JSON.parse(await readFile('test-results/bulk/state.json','utf8'));
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1536,height:1024}});
const errors=[];page.on('pageerror',e=>errors.push(e.message));
try {
  await page.goto('http://localhost:3000/',{waitUntil:'networkidle'});
  await expect(page.getByRole('button',{name:/Bulk GPT Image/})).toHaveCount(2);
  await page.getByRole('button',{name:'Bulk GPT Image',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Bulk GPT Image',level:2})).toBeVisible();
  await page.getByRole('combobox',{name:'Project',exact:true}).selectOption(state.projectId);
  await page.getByRole('button',{name:/Bulk acceptance GPT_IMAGE/}).click();
  await expect(page.getByRole('progressbar',{name:'Batch completion'})).toBeVisible();
  await page.getByRole('button',{name:'task-000',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Original prompt'})).toBeVisible();
  await page.screenshot({path:'test-results/bulk/image-dashboard.png',fullPage:true});
  await page.getByRole('button',{name:'Close details'}).click();
  await page.getByLabel('Task status').selectOption('FAILED');
  await page.getByRole('button',{name:'invalid',exact:true}).click();
  await expect(page.getByRole('button',{name:'retry',exact:true})).toBeDisabled();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button',{name:'Bulk Gemini Video',exact:true}).click();
  await page.getByRole('combobox',{name:'Project',exact:true}).selectOption(state.projectId);
  await page.getByRole('button',{name:/Bulk acceptance GEMINI_VIDEO/}).click();
  await page.getByRole('button',{name:'task-000',exact:true}).click();
  await expect(page.getByAltText('reference.png')).toBeVisible();
  await page.screenshot({path:'test-results/bulk/video-dashboard.png',fullPage:true});
  await page.setViewportSize({width:1100,height:850});
  await expect(page.getByRole('heading',{name:'Bulk Gemini Video',level:2})).toBeVisible();
  await page.screenshot({path:'test-results/bulk/video-narrow.png',fullPage:true});
  expect(errors).toEqual([]);
  console.log('PASS: bulk cards, both pages, scoped history, live progress, prompts, invalid-task controls, reference previews, narrow viewport and no browser runtime errors.');
} finally {await browser.close();}
