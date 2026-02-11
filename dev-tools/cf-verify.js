const puppeteer = require('puppeteer');

async function main() {
  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  const page = pages.find(p => p.url().includes('cloudflare.com/profile'));

  // Click "Send Verification Code" button
  const sent = await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      if (btn.textContent.includes('Send Verification')) {
        btn.click();
        return 'clicked';
      }
    }
    return 'not-found';
  });
  console.log('Send Verification Code:', sent);

  await new Promise(r => setTimeout(r, 3000));
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-verify-sent.png' });
  console.log('Screenshot saved');

  // Check what's shown now
  const dialogText = await page.evaluate(() => {
    const modals = document.querySelectorAll('[role="dialog"], .modal, [class*="Modal"]');
    for (const m of modals) {
      if (m.textContent.includes('Verif') || m.textContent.includes('code')) {
        return {
          text: m.textContent.substring(0, 300),
          inputs: Array.from(m.querySelectorAll('input')).map(i => ({
            type: i.type, id: i.id, placeholder: i.placeholder, name: i.name
          }))
        };
      }
    }
    return 'no dialog';
  });
  console.log('Dialog after send:', JSON.stringify(dialogText, null, 2));

  browser.disconnect();
}

main().catch(console.error);
