const puppeteer = require('puppeteer');

const RECORDS = [
  { type: 'A', name: '@', content: '66.241.124.70' },
  { type: 'AAAA', name: '@', content: '2a09:8280:1::d0:78af:0' },
  { type: 'CNAME', name: '_acme-challenge', content: 'karmarent.app.knkqdwo.flydns.net' },
];

async function main() {
  const recordIndex = parseInt(process.argv[2] || '0');
  const record = RECORDS[recordIndex];
  if (!record) { console.log('Use: node cf-add-one.js 0|1|2'); return; }

  const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222', defaultViewport: null });
  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('cloudflare.com') && p.url().includes('dns'));

  if (!page) {
    page = await browser.newPage();
  }

  // Navigate fresh
  await page.goto('https://dash.cloudflare.com/bdbd784a32d8b0d9a75bf243b09e9c7d/karmarent.app/dns/records', {
    waitUntil: 'networkidle2', timeout: 30000
  });
  await new Promise(r => setTimeout(r, 2000));

  // Zoom out for more context
  await page.evaluate(() => document.body.style.zoom = '0.7');
  await new Promise(r => setTimeout(r, 500));

  console.log(`Adding: ${record.type} ${record.name} → ${record.content}`);

  // Click "Add record"
  await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      if (btn.textContent.trim() === 'Add record') { btn.click(); return; }
    }
  });
  await new Promise(r => setTimeout(r, 2000));

  // Screenshot form
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-form-open.png' });

  // Type selection - default is A, need to change for AAAA and CNAME
  if (record.type !== 'A') {
    // The type dropdown is a react-select. Find its input and interact
    const rsInput = await page.$('[id^="react-select"][id$="-input"]');
    if (rsInput) {
      await rsInput.click();
      await new Promise(r => setTimeout(r, 300));
      await rsInput.type(record.type, { delay: 50 });
      await new Promise(r => setTimeout(r, 500));
      await page.keyboard.press('Enter');
      await new Promise(r => setTimeout(r, 500));
      console.log(`Type selected: ${record.type}`);
    }
  }

  // Tab to Name field and type
  // Find Name input by its label
  const nameClicked = await page.evaluate(() => {
    // All visible inputs that aren't search or react-select
    const inputs = Array.from(document.querySelectorAll('input'));
    for (const inp of inputs) {
      const wrapper = inp.closest('.FormFieldBody') || inp.closest('[class*="field"]') || inp.parentElement;
      const wrapperText = wrapper?.textContent || '';
      if (wrapperText.includes('Name') && wrapperText.includes('required') && !inp.id.includes('react-select') && inp.id !== 'searchIdDnsRecords') {
        inp.focus();
        inp.click();
        return inp.id || 'found-unnamed';
      }
    }
    return null;
  });

  if (nameClicked) {
    console.log('Name input found:', nameClicked);
    await page.keyboard.type(record.name === '@' ? '@' : record.name, { delay: 30 });
    console.log('Name typed');
  } else {
    console.log('Name input NOT found, trying Tab navigation');
    // Click the type dropdown area and Tab forward
    await page.keyboard.press('Tab');
    await new Promise(r => setTimeout(r, 200));
    await page.keyboard.type(record.name === '@' ? '@' : record.name, { delay: 30 });
  }

  await new Promise(r => setTimeout(r, 300));

  // Tab to Content field and type
  const contentClicked = await page.evaluate((recType) => {
    const searchTerms = recType === 'A' ? ['IPv4', 'address'] :
                        recType === 'AAAA' ? ['IPv6', 'address'] :
                        ['Target', 'target'];
    const inputs = Array.from(document.querySelectorAll('input'));
    for (const inp of inputs) {
      const wrapper = inp.closest('.FormFieldBody') || inp.closest('[class*="field"]') || inp.parentElement?.parentElement;
      const wrapperText = wrapper?.textContent || '';
      if (searchTerms.some(t => wrapperText.includes(t)) && !inp.id.includes('react-select') && inp.id !== 'searchIdDnsRecords') {
        inp.focus();
        inp.click();
        return inp.id || 'found-unnamed';
      }
    }
    return null;
  }, record.type);

  if (contentClicked) {
    console.log('Content input found:', contentClicked);
    await page.keyboard.type(record.content, { delay: 20 });
    console.log('Content typed');
  } else {
    console.log('Content input NOT found, trying Tab');
    await page.keyboard.press('Tab');
    await new Promise(r => setTimeout(r, 200));
    await page.keyboard.type(record.content, { delay: 20 });
  }

  await new Promise(r => setTimeout(r, 500));

  // Disable proxy (for A/AAAA records)
  if (record.type !== 'CNAME') {
    const proxyResult = await page.evaluate(() => {
      const buttons = document.querySelectorAll('button[role="switch"]');
      for (const btn of buttons) {
        if (btn.getAttribute('aria-checked') === 'true') {
          btn.click();
          return 'disabled';
        }
      }
      return 'already-off-or-not-found';
    });
    console.log('Proxy:', proxyResult);
  }

  await new Promise(r => setTimeout(r, 500));

  // Screenshot before save
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-before-save.png' });
  console.log('Pre-save screenshot taken. Check cf-before-save.png');

  // DON'T auto-save - wait for user verification
  console.log('\nForm filled. Check screenshot. Run with --save to save.');

  if (process.argv.includes('--save')) {
    const saveResult = await page.evaluate(() => {
      const btns = document.querySelectorAll('button');
      for (const btn of btns) {
        if (btn.textContent.trim() === 'Save') { btn.click(); return 'clicked'; }
      }
      return 'not-found';
    });
    console.log('Save:', saveResult);
    await new Promise(r => setTimeout(r, 3000));
    await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-after-save.png' });
    console.log('Post-save screenshot saved');
  }

  browser.disconnect();
}

main().catch(console.error);
