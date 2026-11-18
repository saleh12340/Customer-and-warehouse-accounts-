let currentView = 'dashboard';
let customersData = [];
let suppliersData = [];
let inventoryData = [];
let invoicesData = [];
let cartItems = [];
let editingInvoiceId = null;
let activeCustomerId = null;

function switchView(viewName) {
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
  document.querySelectorAll('.bottom-nav button').forEach(b => b.classList.remove('active'));

  const viewEl = document.getElementById(`view-${viewName}`);
  if (viewEl) viewEl.classList.add('active');

  const navEl = document.getElementById(`nav-${viewName}`);
  if (navEl) navEl.classList.add('active');

  currentView = viewName;
  loadViewData(viewName);
}

function loadViewData(view) {
  if (window.Android) {
    if (view === 'dashboard') {
      window.Android.getDashboardData();
    } else if (view === 'customers') {
      window.Android.getCustomers();
    } else if (view === 'suppliers') {
      window.Android.getSuppliers();
    } else if (view === 'inventory') {
      window.Android.getInventory();
    } else if (view === 'invoices') {
      window.Android.getInvoices();
    } else if (view === 'reports') {
      window.Android.getReports();
    } else if (view === 'settings') {
      window.Android.getBluetoothDevices();
    }
  }
}

// Callbacks from Android Kotlin Bridge
function onDashboardData(jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    document.getElementById('dash-sales').innerText = data.sales + ' ر.ي';
    document.getElementById('dash-debts').innerText = data.debts + ' ر.ي';
    document.getElementById('dash-payables').innerText = data.payables + ' ر.ي';
    document.getElementById('dash-inventory').innerText = data.inventoryVal + ' ر.ي';
  } catch (e) { console.error(e); }
}

function onCustomersData(jsonStr) {
  try {
    customersData = JSON.parse(jsonStr);
    renderCustomers(customersData);
  } catch (e) { console.error(e); }
}

function renderCustomers(list) {
  const container = document.getElementById('customers-list');
  container.innerHTML = '';
  list.forEach(c => {
    const isDebit = c.balance >= 0;
    const colorClass = isDebit ? 'text-danger' : 'text-success';
    const arrow = isDebit ? '▼' : '▲';
    const div = document.createElement('div');
    div.className = 'list-item';
    div.onclick = () => openCustomerDetail(c.id);
    div.innerHTML = `
      <div>
        <strong>${c.name}</strong> <span class="badge badge-blue">#${c.id}</span>
        <div style="font-size: 12px; color: var(--text-secondary);">${c.phone || 'بدون هاتف'}</div>
      </div>
      <div style="text-align: left;">
        <span class="${colorClass}" style="font-weight: bold; font-size: 15px;">${Math.abs(c.balance)} ر.ي ${arrow}</span>
      </div>
    `;
    container.appendChild(div);
  });
}

function filterCustomers() {
  const query = document.getElementById('customer-search').value.toLowerCase();
  const filtered = customersData.filter(c => c.name.toLowerCase().includes(query) || (c.phone && c.phone.includes(query)));
  renderCustomers(filtered);
}

function openCustomerDetail(customerId) {
  activeCustomerId = customerId;
  switchView('customer-detail');
  if (window.Android) {
    window.Android.getCustomerDetail(customerId);
  }
}

function onCustomerDetailData(jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    document.getElementById('detail-customer-name').innerText = data.customer.name;
    const colorClass = data.customer.balance >= 0 ? 'text-danger' : 'text-success';
    document.getElementById('customer-info-card').innerHTML = `
      <div><strong>الهاتف:</strong> ${data.customer.phone || 'غير متوفر'}</div>
      <div><strong>العنوان:</strong> ${data.customer.address || 'غير متوفر'}</div>
      <div style="margin-top: 6px;"><strong>الرصيد الحالي:</strong> <span class="${colorClass}" style="font-weight: bold;">${data.customer.balance} ر.ي</span></div>
    `;
  } catch (e) { console.error(e); }
}

function submitCustomerTx(isDebit) {
  const amount = parseFloat(document.getElementById('tx-amount').value);
  const desc = document.getElementById('tx-desc').value;
  if (!amount || amount <= 0) {
    alert('الرجاء إدخال مبلغ صحيح');
    return;
  }
  if (window.Android) {
    window.Android.addCustomerTransaction(activeCustomerId, amount, isDebit, desc);
    document.getElementById('tx-amount').value = '';
    document.getElementById('tx-desc').value = '';
    openCustomerDetail(activeCustomerId);
  }
}

function shareCustomerWhatsApp() {
  if (window.Android && activeCustomerId) {
    window.Android.shareCustomerWhatsApp(activeCustomerId);
  }
}

function onSuppliersData(jsonStr) {
  try {
    suppliersData = JSON.parse(jsonStr);
    const container = document.getElementById('suppliers-list');
    container.innerHTML = '';
    suppliersData.forEach(s => {
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML = `
        <div>
          <strong>${s.name}</strong>
          <div style="font-size: 12px; color: var(--text-secondary);">${s.company} | ${s.phone}</div>
        </div>
        <div class="text-primary" style="font-weight: bold;">${s.balance} ر.ي</div>
      `;
      container.appendChild(div);
    });
  } catch (e) { console.error(e); }
}

function onInventoryData(jsonStr) {
  try {
    inventoryData = JSON.parse(jsonStr);
    const container = document.getElementById('inventory-list');
    container.innerHTML = '';
    inventoryData.forEach(i => {
      const low = i.quantity <= 10;
      const badge = low ? '<span class="badge badge-red">منخفض</span>' : '<span class="badge badge-green">متوفر</span>';
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML = `
        <div>
          <strong>${i.name}</strong> ${badge}
          <div style="font-size: 12px; color: var(--text-secondary);">شراء: ${i.purchasePrice} | بيع: ${i.sellingPrice} (${i.unit})</div>
        </div>
        <div style="font-weight: bold; font-size: 15px;">الكمية: ${i.quantity}</div>
      `;
      container.appendChild(div);
    });
  } catch (e) { console.error(e); }
}

function onInvoicesData(jsonStr) {
  try {
    invoicesData = JSON.parse(jsonStr);
    const container = document.getElementById('invoices-list');
    container.innerHTML = '';
    invoicesData.forEach(inv => {
      const statusBadge = inv.status === 'PAID' ? '<span class="badge badge-green">مدفوع</span>' : '<span class="badge badge-red">غير مدفوع</span>';
      const div = document.createElement('div');
      div.className = 'list-item';
      div.onclick = () => openEditInvoice(inv.id);
      div.innerHTML = `
        <div>
          <strong>فاتورة #${inv.invoiceNumber}</strong> ${statusBadge}
          <div style="font-size: 12px; color: var(--text-secondary);">${inv.partyName} | ${inv.type}</div>
        </div>
        <div style="text-align: left; font-weight: bold;">
          <div>${inv.totalAmount} ر.ي</div>
          <div style="font-size: 11px; color: var(--primary-blue);">اضغط للتعديل</div>
        </div>
      `;
      container.appendChild(div);
    });
  } catch (e) { console.error(e); }
}

// Invoice creation & editing
function openCreateInvoice() {
  editingInvoiceId = null;
  cartItems = [];
  document.getElementById('invoice-screen-title').innerText = 'إصدار فاتورة جديدة';
  document.getElementById('inv-number').value = 'AZ-' + Math.floor(100000 + Math.random() * 900000);
  document.getElementById('inv-paid').value = '0';
  populatePartyDropdown();
  populateItemDropdown();
  renderCart();
  switchView('create-invoice');
}

function openEditInvoice(invoiceId) {
  editingInvoiceId = invoiceId;
  if (window.Android) {
    window.Android.getInvoiceDetails(invoiceId);
  }
}

function onInvoiceLoadedForEdit(jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    document.getElementById('invoice-screen-title').innerText = 'تعديل الفاتورة';
    document.getElementById('inv-number').value = data.invoice.invoiceNumber;
    document.getElementById('inv-type').value = data.invoice.type;
    document.getElementById('inv-paid').value = data.invoice.paidAmount;
    onInvoiceTypeChange();
    document.getElementById('inv-party').value = data.invoice.partyId;
    cartItems = data.items.map(i => ({ itemId: i.itemId, itemName: i.itemName, quantity: i.quantity, unitPrice: i.unitPrice, totalPrice: i.totalPrice }));
    populateItemDropdown();
    renderCart();
    switchView('create-invoice');
  } catch (e) { console.error(e); }
}

function onInvoiceTypeChange() {
  const type = document.getElementById('inv-type').value;
  const label = document.getElementById('party-label');
  if (type === 'PURCHASE') {
    label.innerText = 'المورد';
    populateSuppliersDropdown();
  } else {
    label.innerText = 'العميل';
    populateCustomersDropdown();
  }
}

function populatePartyDropdown() {
  onInvoiceTypeChange();
}

function populateCustomersDropdown() {
  const select = document.getElementById('inv-party');
  select.innerHTML = '<option value="0">عميل نقدي بدون حساب</option>';
  customersData.forEach(c => {
    select.innerHTML += `<option value="${c.id}">${c.name} (${c.balance} ر.ي)</option>`;
  });
}

function populateSuppliersDropdown() {
  const select = document.getElementById('inv-party');
  select.innerHTML = '';
  suppliersData.forEach(s => {
    select.innerHTML += `<option value="${s.id}">${s.name} (${s.company})</option>`;
  });
}

function populateItemDropdown() {
  const select = document.getElementById('inv-item-select');
  select.innerHTML = '<option value="">اختر صنف من المخزن...</option>';
  inventoryData.forEach(i => {
    const type = document.getElementById('inv-type').value;
    const price = type === 'PURCHASE' ? i.purchasePrice : i.sellingPrice;
    select.innerHTML += `<option value="${i.id}" data-price="${price}">${i.name} (متوفر: ${i.quantity}) - ${price} ر.ي</option>`;
  });
}

function onItemSelect() {
  const select = document.getElementById('inv-item-select');
  const opt = select.options[select.selectedIndex];
  if (opt && opt.dataset.price) {
    document.getElementById('inv-item-price').value = opt.dataset.price;
  }
}

function addCartItem() {
  const select = document.getElementById('inv-item-select');
  const itemId = parseInt(select.value);
  if (!itemId) {
    alert('الرجاء اختيار صنف');
    return;
  }
  const itemName = select.options[select.selectedIndex].text.split('(')[0].trim();
  const qty = parseInt(document.getElementById('inv-item-qty').value) || 1;
  const price = parseFloat(document.getElementById('inv-item-price').value) || 0;
  const total = qty * price;

  cartItems.push({ itemId, itemName, quantity: qty, unitPrice: price, totalPrice: total });
  renderCart();
}

function removeCartItem(index) {
  cartItems.splice(index, 1);
  renderCart();
}

function clearCart() {
  cartItems = [];
  renderCart();
}

function renderCart() {
  const tbody = document.getElementById('cart-items-tbody');
  tbody.innerHTML = '';
  let subtotal = 0;
  cartItems.forEach((item, idx) => {
    subtotal += item.totalPrice;
    tbody.innerHTML += `
      <tr>
        <td>${item.itemName}</td>
        <td>${item.quantity}</td>
        <td>${item.unitPrice}</td>
        <td><strong>${item.totalPrice}</strong></td>
        <td><button class="btn btn-danger" style="padding: 2px 6px; font-size: 11px; margin:0;" onclick="removeCartItem(${idx})">حذف</button></td>
      </tr>
    `;
  });
  updateInvoiceTotals();
}

function updateInvoiceTotals() {
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  const paid = parseFloat(document.getElementById('inv-paid').value) || 0;
  const remaining = subtotal - paid;
  document.getElementById('inv-total-display').innerText = `الإجمالي: ${subtotal} ر.ي`;
  document.getElementById('inv-remaining-display').innerText = `المتبقي: ${remaining} ر.ي`;
}

function saveInvoice() {
  const number = document.getElementById('inv-number').value;
  const type = document.getElementById('inv-type').value;
  const partyId = parseInt(document.getElementById('inv-party').value) || 0;
  const partySelect = document.getElementById('inv-party');
  const partyName = partySelect.options[partySelect.selectedIndex] ? partySelect.options[partySelect.selectedIndex].text.split('(')[0].trim() : 'نقدي';
  const paid = parseFloat(document.getElementById('inv-paid').value) || 0;

  if (cartItems.length === 0) {
    alert('الرجاء إضافة أصناف للفاتورة');
    return;
  }

  if (window.Android) {
    const payload = {
      id: editingInvoiceId,
      invoiceNumber: number,
      type: type,
      partyId: partyId,
      partyName: partyName,
      items: cartItems,
      paidAmount: paid
    };
    window.Android.saveInvoice(JSON.stringify(payload));
  }
}

function onReportsData(jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const container = document.getElementById('reports-list');
    container.innerHTML = `
      <div class="card">
        <h3>الملخص المالي العام</h3>
        <div style="margin-top: 10px;">
          <div style="display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--border-color);">
            <span>إجمالي المبيعات:</span>
            <strong class="text-success">${data.sales} ر.ي</strong>
          </div>
          <div style="display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--border-color);">
            <span>إجمالي المشتريات:</span>
            <strong class="text-primary">${data.purchases} ر.ي</strong>
          </div>
          <div style="display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--border-color);">
            <span>ديون العملاء:</span>
            <strong class="text-danger">${data.debts} ر.ي</strong>
          </div>
          <div style="display: flex; justify-content: space-between; padding: 6px 0;">
            <span>قيمة المخزون:</span>
            <strong>${data.inventoryVal} ر.ي</strong>
          </div>
        </div>
      </div>
    `;
  } catch (e) { console.error(e); }
}

function onBluetoothDevices(jsonStr) {
  try {
    const devices = JSON.parse(jsonStr);
    const container = document.getElementById('bluetooth-devices-list');
    container.innerHTML = '';
    if (devices.length === 0) {
      container.innerHTML = '<p style="font-size: 13px; color: var(--text-secondary);">لا توجد أجهزة بلوتوث مقترنة</p>';
      return;
    }
    devices.forEach(d => {
      container.innerHTML += `<button class="btn btn-outline" style="margin-top: 6px;" onclick="connectPrinter('${d.address}')">${d.name} (${d.address})</button>`;
    });
  } catch (e) { console.error(e); }
}

function connectPrinter(address) {
  if (window.Android) {
    window.Android.connectPrinter(address);
    document.getElementById('printer-status-text').innerText = 'حالة الطابعة: متصل';
  }
}

function printTestReceipt() {
  if (window.Android) {
    window.Android.printTestReceipt();
  }
}

function triggerBackup() {
  if (window.Android) {
    window.Android.exportBackup();
  }
}

function triggerRestore() {
  if (window.Android) {
    window.Android.importBackup();
  }
}

function showToast(msg) {
  alert(msg);
}

// Initial load
window.onload = function() {
  switchView('dashboard');
};
