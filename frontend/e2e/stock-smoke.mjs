import {chromium, expect} from '@playwright/test';
import {mkdir} from 'node:fs/promises';

// Read-only verification of the deployed application. Seed with scripts/stock-smoke.ps1.
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1536,height:1024}});
const errors=[];
page.on('pageerror',error=>errors.push(error.message));
try {
  await page.goto(process.env.MEDIA_FACTORY_URL??'http://localhost:3000',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'Stock Factory',exact:true}).click();
  await expect(page.getByRole('heading',{name:'From an idea to a ready collection.'})).toBeVisible();
  await expect(page.getByRole('button',{name:'Review stock',exact:true}).first()).toBeVisible();
  await mkdir('test-results',{recursive:true});
  await page.screenshot({path:'test-results/stock-grid.png',fullPage:true});
  await page.getByRole('button',{name:'Review stock',exact:true}).first().click();
  const dialog=page.getByRole('dialog',{name:'Stock review'});
  await expect(dialog.getByRole('img',{name:'Stock master review'})).toBeVisible();
  await expect(dialog.getByText('APPROVED',{exact:true})).toBeVisible();
  await expect(dialog.getByText(/AI disclosure: true/)).toBeVisible();
  await dialog.getByText('Technical validation',{exact:true}).click();
  await expect(dialog.locator('pre').filter({hasText:'MEGAPIXELS'})).toBeVisible();
  await dialog.screenshot({path:'test-results/stock-review.png'});
  await dialog.getByRole('button',{name:'Edit metadata',exact:true}).click();
  await expect(dialog.getByRole('textbox',{name:'Keyword 1',exact:true})).toBeVisible();
  await expect(dialog.getByRole('button',{name:'Move keyword 1 down',exact:true})).toBeVisible();
  await dialog.getByRole('button',{name:'Close stock review'}).click();
  await page.locator('.stock-tabs').getByRole('button',{name:'Exports',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Immutable export history'})).toBeVisible();
  await expect(page.getByRole('link',{name:'Download ZIP'}).first()).toBeVisible();
  await page.screenshot({path:'test-results/stock-exports.png',fullPage:true});
  await page.locator('.stock-tabs').getByRole('button',{name:'Profiles',exact:true}).click();
  await expect(page.getByRole('heading',{name:'STOCK_ADOBE · v1'})).toBeVisible();
  await expect(page.getByText('Requires platform review',{exact:true})).toBeVisible();
  await page.setViewportSize({width:1100,height:850});
  await page.locator('.stock-tabs').getByRole('button',{name:'Factory',exact:true}).click();
  await expect(page.getByRole('heading',{name:'From an idea to a ready collection.'})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  expect(errors).toEqual([]);
  console.log('PASS: stock dashboard, review evidence, keyword editor, immutable export downloads, disabled Adobe profile and responsive viewport; zero browser errors.');
} finally {await browser.close();}
