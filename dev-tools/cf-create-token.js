const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  const page = pages.find(p => p.url().includes('cloudflare.com/profile'));

  if (!page) {
    console.log('No Cloudflare profile page');
    browser.disconnect();
    return;
  }

  // Close any open modal first
  await page.keyboard.press('Escape');
  await new Promise(r => setTimeout(r, 500));

  // Click "Create Token"
  const clicked = await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      if (btn.textContent.trim() === 'Create Token') {
        btn.click();
        return 'clicked';
      }
    }
    return 'not-found';
  });
  console.log('Create Token:', clicked);

  await new Promise(r => setTimeout(r, 3000));
  await page.evaluate(() => document.body.style.zoom = '0.6');
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-create-token.png' });
  console.log('Screenshot saved');

  // Look for "Edit zone DNS" template or custom token option
  const options = await page.evaluate(() => {
    const items = document.querySelectorAll('button, a, [role="button"]');
    return Array.from(items)
      .filter(i => i.textContent.includes('DNS') || i.textContent.includes('Edit') || i.textContent.includes('Use template') || i.textContent.includes('custom'))
      .map(i => ({ text: i.textContent.trim().substring(0, 60), tag: i.tagName }));
  });
  console.log('Template options:', JSON.stringify(options, null, 2));

  browser.disconnect();
}

main().catch(console.error);
