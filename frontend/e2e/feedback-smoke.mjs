import {chromium,expect} from '@playwright/test';
import {mkdir,writeFile} from 'node:fs/promises';
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1536,height:1024}});
const errors=[];
page.on('pageerror',e=>errors.push(e.message));
try {
  await page.goto(process.env.MEDIA_FACTORY_URL??'http://localhost:3000',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'Feedback',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Feedback & experiments',exact:true})).toBeVisible();
  const sections=['Overview','Visual Attributes','Findings','Hypotheses','Experiment Proposals','Experiments','Saturation','Learnings','Data Quality'];
  for(const name of sections){
    await page.getByRole('navigation',{name:'Feedback sections'}).getByRole('button',{name,exact:true}).click();
    await expect(page.getByText('Loading evidence…')).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
  }
  await page.getByRole('navigation',{name:'Feedback sections'}).getByRole('button',{name:'Overview',exact:true}).click();
  await page.getByLabel('Feedback metric',{exact:true}).selectOption('QA_APPROVAL_RATE');
  await page.getByLabel('Feedback period',{exact:true}).selectOption('LIFETIME');
  await expect(page.getByRole('button',{name:'Analyze patterns',exact:true})).toBeDisabled();
  await mkdir('test-results',{recursive:true});
  await page.screenshot({path:'test-results/feedback-desktop.png',fullPage:true});
  await page.setViewportSize({width:1100,height:850});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/feedback-compact.png',fullPage:true});
  expect(errors).toEqual([]);
  await writeFile('test-results/feedback-smoke.json',JSON.stringify({passed:true,sections,browserErrors:errors,viewports:[1536,1100]},null,2));
  console.log('PASS: nine Feedback sections, filters, scope guard, responsive layout; no browser errors.');
} finally {await browser.close();}
