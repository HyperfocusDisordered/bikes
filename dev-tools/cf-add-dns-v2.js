const puppeteer = require('puppeteer');

const RECORDS = [
  { type: 'A', name: '@', content: '66.241.124.70' },
  { type: 'AAAA', name: '@', content: '2a09:8280:1::d0:78af:0' },
  { type: 'CNAME', name: '_acme-challenge', content: 'karmarent.app.knkqdwo.flydns.net' },
];

async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  const pages = await browser.pages();
  let page = pages.find(p => p.url().includes('cloudflare.com') && p.url().includes('dns'));

  if (!page) {
    page = await browser.newPage();
    await page.goto('https://dash.cloudflare.com/bdbd784a32d8b0d9a75bf243b09e9c7d/karmarent.app/dns/records', {
      waitUntil: 'networkidle2',
      timeout: 30000
    });
    await new Promise(r => setTimeout(r, 3000));
  }

  console.log('On DNS page');

  // Process one record at a time
  const recordIndex = parseInt(process.argv[2] || '0');
  const record = RECORDS[recordIndex];

  if (!record) {
    console.log('Invalid record index. Use 0, 1, or 2');
    browser.disconnect();
    return;
  }

  console.log(`Adding record ${recordIndex + 1}: ${record.type} ${record.name} → ${record.content}`);

  // Scroll up first
  await page.evaluate(() => window.scrollTo(0, 0));
  await new Promise(r => setTimeout(r, 500));

  // Click "Add record" button
  await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      if (btn.textContent.trim() === 'Add record') {
        btn.click();
        return true;
      }
    }
    return false;
  });
  await new Promise(r => setTimeout(r, 1500));

  // Screenshot to see form
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-step1-form.png`, fullPage: true });

  // Step 1: Select type from dropdown (it's a React Select, not native <select>)
  if (record.type !== 'A') {
    // Click the type dropdown (react-select)
    const reactSelectInput = await page.$('[id^="react-select"][id$="-input"]');
    if (reactSelectInput) {
      // Click the dropdown container
      const container = await page.evaluateHandle(el => el.closest('[class*="control"]') || el.parentElement, reactSelectInput);
      await container.click();
      await new Promise(r => setTimeout(r, 500));

      // Type the record type to filter
      await reactSelectInput.type(record.type, { delay: 50 });
      await new Promise(r => setTimeout(r, 500));

      // Press Enter to select
      await page.keyboard.press('Enter');
      await new Promise(r => setTimeout(r, 500));
    } else {
      // Fallback: try native select
      await page.evaluate((type) => {
        const sel = document.querySelector('select');
        if (sel) {
          sel.value = type;
          sel.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }, record.type);
    }
    console.log(`Selected type: ${record.type}`);
  } else {
    console.log('Type A is default');
  }

  await new Promise(r => setTimeout(r, 500));
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-step2-type.png`, fullPage: true });

  // Step 2: Fill Name field using Puppeteer's type (keyboard simulation)
  // Find the Name input
  const nameInput = await page.evaluateHandle(() => {
    const labels = document.querySelectorAll('label');
    for (const label of labels) {
      if (label.textContent.includes('Name')) {
        const input = label.querySelector('input') || label.nextElementSibling?.querySelector('input');
        if (input) return input;
      }
    }
    // Alternative: find by placeholder or nearby text
    const inputs = document.querySelectorAll('input');
    for (const input of inputs) {
      const parent = input.closest('div');
      if (parent && parent.textContent.includes('Name') && !parent.textContent.includes('Search')) {
        return input;
      }
    }
    return null;
  });

  if (nameInput) {
    await nameInput.click();
    await new Promise(r => setTimeout(r, 200));
    // Clear existing content
    await page.keyboard.down('Meta');
    await page.keyboard.press('a');
    await page.keyboard.up('Meta');
    await page.keyboard.press('Backspace');
    // Type new value
    const nameValue = record.name === '@' ? '@' : record.name;
    await page.keyboard.type(nameValue, { delay: 30 });
    console.log(`Typed name: ${nameValue}`);
  } else {
    console.log('Name input not found');
  }

  await new Promise(r => setTimeout(r, 500));

  // Step 3: Fill Content/IPv4/IPv6/Target field
  const contentInput = await page.evaluateHandle((recType) => {
    const labels = document.querySelectorAll('label');
    const labelText = recType === 'A' ? 'IPv4' :
                      recType === 'AAAA' ? 'IPv6' :
                      recType === 'CNAME' ? 'Target' : 'Content';

    for (const label of labels) {
      if (label.textContent.includes(labelText) || label.textContent.includes('address') || label.textContent.includes('target')) {
        const input = label.querySelector('input') || label.nextElementSibling?.querySelector('input');
        if (input) return input;
      }
    }

    // Alternative search
    const inputs = document.querySelectorAll('input');
    for (const input of inputs) {
      const parent = input.closest('div');
      if (parent && (parent.textContent.includes(labelText) || parent.textContent.includes('address'))) {
        if (input.id !== 'searchIdDnsRecords' && !input.id.includes('react-select')) {
          return input;
        }
      }
    }
    return null;
  }, record.type);

  if (contentInput) {
    await contentInput.click();
    await new Promise(r => setTimeout(r, 200));
    await page.keyboard.down('Meta');
    await page.keyboard.press('a');
    await page.keyboard.up('Meta');
    await page.keyboard.press('Backspace');
    await page.keyboard.type(record.content, { delay: 20 });
    console.log(`Typed content: ${record.content}`);
  } else {
    console.log('Content input not found');
  }

  await new Promise(r => setTimeout(r, 500));

  // Step 4: Disable proxy (DNS only) - click the toggle if it's on
  await page.evaluate(() => {
    // Find proxy toggle - it's usually a button with role="switch"
    const switches = document.querySelectorAll('[role="switch"], .toggle, input[type="checkbox"]');
    for (const sw of switches) {
      const nearText = sw.closest('div')?.textContent || '';
      if (nearText.includes('Proxy') || nearText.includes('proxy')) {
        // If proxy is ON (orange cloud), click to turn off
        if (sw.getAttribute('aria-checked') === 'true' || sw.checked) {
          sw.click();
          return 'turned-off';
        }
        return 'already-off';
      }
    }
    return 'not-found';
  });
  console.log('Proxy toggle handled');

  await new Promise(r => setTimeout(r, 500));
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-step3-filled.png`, fullPage: true });
  console.log('Screenshot of filled form saved');

  // Step 5: Click Save
  const saveResult = await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      const text = btn.textContent.trim();
      if (text === 'Save' || text === 'Save record') {
        btn.click();
        return text;
      }
    }
    return 'not-found';
  });
  console.log('Save button:', saveResult);

  await new Promise(r => setTimeout(r, 3000));
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-step4-result.png`, fullPage: true });
  console.log('Result screenshot saved');

  browser.disconnect();
  console.log('Done! Check screenshots to verify.');
}

main().catch(console.error);
