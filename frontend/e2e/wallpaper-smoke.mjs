import {chromium, expect} from '@playwright/test';
import {mkdir} from 'node:fs/promises';

// Read-only deployed UI verification. Seed with scripts/wallpaper-smoke.ps1.
const browser = await chromium.launch({headless:true});
const page = await browser.newPage({viewport:{width:1536,height:1024}});
const errors=[];
page.on('pageerror',e=>errors.push(e.message));
try {
  await page.goto(process.env.MEDIA_FACTORY_URL??'http://localhost:3000', {waitUntil:'networkidle'});
  await page.getByRole('button',{name:'Wallpaper Factory',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Made for the everyday canvas.'})).toBeVisible();
  await expect(page.getByRole('button',{name:'Inspect wallpaper'}).first()).toBeVisible();
  await mkdir('test-results',{recursive:true});
  await page.screenshot({path:'test-results/wallpaper-grid.png',fullPage:true});
  await page.getByRole('button',{name:'AMOLED Review',exact:true}).click();
  await page.getByRole('button',{name:'Inspect wallpaper'}).first().click();
  const dialog=page.getByRole('dialog',{name:'Wallpaper review'});
  await expect(dialog.getByRole('heading',{name:'AMOLED_SUITABLE'})).toBeVisible();
  await expect(dialog.getByText('QA decision: APPROVED')).toBeVisible();
  await expect(dialog.getByRole('img',{name:'ANDROID_THUMBNAIL',exact:true})).toBeVisible();
  await dialog.getByText(/Publication manifest v/).click();
  await expect(dialog.locator('pre').filter({hasText:'publicationVersion'})).toBeVisible();
  await dialog.screenshot({path:'test-results/wallpaper-review.png'});
  await dialog.getByRole('button',{name:'Close wallpaper review'}).click();
  await page.setViewportSize({width:1100,height:850});
  await expect(page.getByRole('heading',{name:'Made for the everyday canvas.'})).toBeVisible();
  expect(errors).toEqual([]);
  console.log('PASS: wallpaper dashboard, AMOLED grid, QA evidence, six variants, publication manifest, responsive viewport; zero browser runtime errors.');
} finally {await browser.close();}
