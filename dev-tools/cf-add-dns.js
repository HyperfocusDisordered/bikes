const puppeteer = require('puppeteer');

const RECORDS = [
  { type: 'A', name: '@', content: '66.241.124.70' },
  { type: 'AAAA', name: '@', content: '2a09:8280:1::d0:78af:0' },
  { type: 'CNAME', name: '_acme-challenge', content: 'karmarent.app.knkqdwo.flydns.net' },
];

async function addRecord(page, record, index) {
  console.log(`\n--- Adding record ${index + 1}: ${record.type} ${record.name} → ${record.content} ---`);

  // Click "Add record" button
  await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      if (btn.textContent.trim() === 'Add record') {
        btn.click();
        return;
      }
    }
  });
  await new Promise(r => setTimeout(r, 1500));

  // Select record type from dropdown
  // First, find and click the type selector
  const typeSelector = await page.evaluate((recordType) => {
    // Look for the type dropdown/select
    const selects = document.querySelectorAll('select');
    for (const sel of selects) {
      const options = Array.from(sel.options);
      if (options.some(o => o.value === 'A' || o.text === 'A')) {
        sel.value = recordType;
        sel.dispatchEvent(new Event('change', { bubbles: true }));
        return 'select-found';
      }
    }

    // Try React-style dropdown - click the type button
    const buttons = document.querySelectorAll('[role="combobox"], [data-testid*="type"], button');
    for (const btn of buttons) {
      if (btn.textContent.trim() === 'A' || btn.textContent.includes('Type')) {
        btn.click();
        return 'button-clicked';
      }
    }

    return 'not-found';
  }, record.type);

  console.log('Type selector result:', typeSelector);
  await new Promise(r => setTimeout(r, 500));

  // Take screenshot to see the form state
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-add-${index + 1}-form.png` });

  // Try to find and interact with form fields
  const formState = await page.evaluate((rec) => {
    const result = {};

    // Find all input fields
    const inputs = document.querySelectorAll('input[type="text"], input:not([type])');
    result.inputCount = inputs.length;
    result.inputs = Array.from(inputs).map(i => ({
      placeholder: i.placeholder,
      name: i.name,
      value: i.value,
      id: i.id,
      ariaLabel: i.getAttribute('aria-label'),
      parentText: i.closest('label')?.textContent?.trim()?.substring(0, 30) || ''
    }));

    // Find select elements
    const selects = document.querySelectorAll('select');
    result.selectCount = selects.length;
    result.selects = Array.from(selects).map(s => ({
      name: s.name,
      value: s.value,
      options: Array.from(s.options).slice(0, 10).map(o => o.value)
    }));

    return result;
  }, record);

  console.log('Form state:', JSON.stringify(formState, null, 2));

  // Now fill in the fields
  // Usually: Type dropdown, Name input, Content/Value input
  await page.evaluate((rec) => {
    const inputs = document.querySelectorAll('input[type="text"], input:not([type])');
    const inputsArr = Array.from(inputs);

    // Find name field (usually has placeholder "Name" or "@")
    const nameInput = inputsArr.find(i =>
      i.placeholder?.toLowerCase()?.includes('name') ||
      i.placeholder === '@' ||
      i.getAttribute('aria-label')?.toLowerCase()?.includes('name')
    );

    // Find content/value field
    const contentInput = inputsArr.find(i =>
      i.placeholder?.toLowerCase()?.includes('content') ||
      i.placeholder?.toLowerCase()?.includes('address') ||
      i.placeholder?.toLowerCase()?.includes('ipv') ||
      i.placeholder?.toLowerCase()?.includes('target') ||
      i.getAttribute('aria-label')?.toLowerCase()?.includes('content') ||
      i.getAttribute('aria-label')?.toLowerCase()?.includes('address') ||
      i.getAttribute('aria-label')?.toLowerCase()?.includes('target')
    );

    if (nameInput) {
      nameInput.focus();
      nameInput.value = '';
      // Use native input setter for React
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype, 'value'
      ).set;
      nativeInputValueSetter.call(nameInput, rec.name === '@' ? '' : rec.name);
      nameInput.dispatchEvent(new Event('input', { bubbles: true }));
      nameInput.dispatchEvent(new Event('change', { bubbles: true }));
    }

    if (contentInput) {
      contentInput.focus();
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype, 'value'
      ).set;
      nativeInputValueSetter.call(contentInput, rec.content);
      contentInput.dispatchEvent(new Event('input', { bubbles: true }));
      contentInput.dispatchEvent(new Event('change', { bubbles: true }));
    }

    return { nameFound: !!nameInput, contentFound: !!contentInput };
  }, record);

  await new Promise(r => setTimeout(r, 500));

  // Handle type selection - for select element
  if (record.type !== 'A') {
    await page.evaluate((recordType) => {
      const selects = document.querySelectorAll('select');
      for (const sel of selects) {
        const options = Array.from(sel.options);
        if (options.some(o => o.value === 'A' || o.value === 'AAAA' || o.value === 'CNAME')) {
          const nativeSetter = Object.getOwnPropertyDescriptor(
            window.HTMLSelectElement.prototype, 'value'
          ).set;
          nativeSetter.call(sel, recordType);
          sel.dispatchEvent(new Event('change', { bubbles: true }));
          return;
        }
      }
    }, record.type);
    await new Promise(r => setTimeout(r, 500));
  }

  // Make sure proxy is off (DNS only) - look for proxy toggle
  await page.evaluate(() => {
    // Find and disable proxy if it's on (orange cloud → grey cloud)
    const toggles = document.querySelectorAll('[role="switch"], input[type="checkbox"]');
    for (const toggle of toggles) {
      const label = toggle.closest('label')?.textContent || '';
      if (label.includes('Proxy') || label.includes('proxy')) {
        if (toggle.checked || toggle.getAttribute('aria-checked') === 'true') {
          toggle.click();
        }
      }
    }
  });

  await new Promise(r => setTimeout(r, 500));
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-add-${index + 1}-filled.png` });
  console.log(`Screenshot saved: cf-add-${index + 1}-filled.png`);

  // Click Save
  const saved = await page.evaluate(() => {
    const btns = document.querySelectorAll('button');
    for (const btn of btns) {
      const text = btn.textContent.trim();
      if (text === 'Save' || text === 'Save record') {
        btn.click();
        return true;
      }
    }
    return false;
  });
  console.log('Clicked Save:', saved);

  await new Promise(r => setTimeout(r, 2000));
  await page.screenshot({ path: `/Users/denisovchar/bikes/dev-tools/cf-add-${index + 1}-after.png` });
}

async function main() {
  const browser = await puppeteer.connect({
    browserURL: 'http://localhost:9222',
    defaultViewport: null
  });

  // Find the Cloudflare DNS page
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

  console.log('On DNS page:', page.url());

  for (let i = 0; i < RECORDS.length; i++) {
    await addRecord(page, RECORDS[i], i);
  }

  console.log('\n=== All records added ===');
  await page.screenshot({ path: '/Users/denisovchar/bikes/dev-tools/cf-dns-final.png' });

  browser.disconnect();
}

main().catch(console.error);
