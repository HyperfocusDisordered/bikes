const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });

  const page = await browser.newPage();
  // Go to API Tokens page
  await page.goto('https://dash.cloudflare.com/profile/api-tokens', {
    waitUntil: 'networkidle2',
    timeout: 30000
  });
  await new Promise(r => setTimeout(r, 3000));

  // Zoom out
  await page.evaluate(() => document.body.style.zoom = '0.6');
  await new Promise(r => setTimeout(r, 300));

  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-api-tokens.png' });
  console.log('Screenshot saved');

  // Look for "Create Token" button
  const buttons = await page.evaluate(() => {
    const btns = document.querySelectorAll('button, a');
    return Array.from(btns)
      .filter(b => b.textContent.includes('Create') || b.textContent.includes('Token') || b.textContent.includes('View'))
      .map(b => ({ text: b.textContent.trim().substring(0, 40), tag: b.tagName, href: b.href || '' }));
  });
  console.log('Relevant buttons:', JSON.stringify(buttons, null, 2));

  browser.disconnect();
}

main().catch(console.error);
