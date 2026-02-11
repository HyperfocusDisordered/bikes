const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  // Find the Cloudflare consent page
  const pages = await browser.pages();
  let page = null;
  for (const p of pages) {
    const url = await p.url();
    if (url.includes('cloudflare.com/oauth/consent')) {
      page = p;
      break;
    }
  }

  if (!page) {
    console.log('Consent page not found. URLs:');
    for (const p of pages) {
      console.log(' -', await p.url());
    }
    browser.disconnect();
    return;
  }

  console.log('Found consent page');

  // Click the Allow button using evaluate
  const clicked = await page.evaluate(() => {
    const buttons = document.querySelectorAll('button');
    for (const btn of buttons) {
      if (btn.textContent.trim() === 'Allow') {
        btn.click();
        return true;
      }
    }
    return false;
  });

  console.log('Clicked Allow:', clicked);

  // Wait for redirect
  await new Promise(r => setTimeout(r, 5000));
  console.log('Final URL:', page.url());

  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-after-allow.png' });

  browser.disconnect();
  console.log('Done');
}

main().catch(console.error);
