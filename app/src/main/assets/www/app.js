'use strict';

let currentView = 'dashboard';
let appCurrency = 'ر.ي';
let storeName = 'بقالة العزي للمواد الغذائية';

let customersData = [];
let suppliersData = [];
let inventoryData = [];
let invoicesData = [];
let settingsData = {
  storeName: 'بقالة العزي للمواد الغذائية',
  storePhone: '',
  storeAddress: '',
  currency: 'ر.ي',
  printerWidthMm: 58,
  printerConnected: false,
  connectedPrinterName: '',
  connectedPrinterAddress: '',
  bluetoothAvailable: false,
  bluetoothEnabled: false
};

let customerFilter = 'ALL';
let inventoryFilter = 'ALL';
let invoiceFilter = 'ALL';
let reportRange = 'ALL';

let activeCustomerId = null;
let activeCustomerDetail = null;
let editingCustomerId = 0;
let editingCustomerTxId = 0;
let pendingDeleteCustomerTxId = 0;

let activeSupplierId = null;
let activeSupplierDetail = null;
let editingSupplierId = 0;
let editingSupplierTxId = 0;
let pendingDeleteSupplierTxId = 0;

let editingInventoryId = 0;
let pendingDeleteInventoryId = 0;

let cartItems = [];
let editingInvoiceId = 0;
let invoiceDirty = false;
let pendingDeleteInvoiceId = 0;
let activePreviewInvoiceId = 0;

let reqSeq = 1;
const pendingCallbacks = {};
let toastTimer = null;

function nextReqId(onSuccess) {
  const id = 'req_' + (reqSeq++);
  if (typeof onSuccess === 'function') {
    pendingCallbacks[id] = onSuccess;
  }
  return id;
}

window.onActionResult = function (reqId, success, message, dataJson) {
  let parsed = null;
  if (dataJson && dataJson !== 'null') {
    try {
      parsed = JSON.parse(dataJson);
    } catch (_) {
      parsed = null;
    }
  }
  showToast(message || (success ? 'تمت العملية بنجاح' : 'تعذر تنفيذ العملية'), success ? 'success' : 'error');
  const cb = pendingCallbacks[reqId];
  if (cb) {
    delete pendingCallbacks[reqId];
    if (success) {
      cb(parsed);
    }
  }
};

function showToast(message, type) {
  const el = document.getElementById('toast-banner');
  if (!el) return;
  el.textContent = message;
  el.className = 'toast-banner toast-' + (type || 'info');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.classList.add('hidden');
  }, 3200);
}

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function fmtMoney(num) {
  const val = Number(num) || 0;
  const rounded = Math.round(val * 100) / 100;
  return rounded.toLocaleString('en-US', {
    minimumFractionDigits: Number.isInteger(rounded) ? 0 : 2,
    maximumFractionDigits: 2
  });
}

function fmtDate(millis) {
  if (!millis) return '';
  const d = new Date(millis);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${y}/${m}/${day} ${hh}:${mm}`;
}

function invoiceTypeAr(type) {
  switch ((type || '').toUpperCase()) {
    case 'CASH': return 'بيع نقدي';
    case 'CREDIT': return 'بيع آجل';
    case 'SALE': return 'مبيعات';
    case 'PURCHASE': return 'مشتريات';
    default: return type || '';
  }
}

function toggleMenu(forceState) {
  const menu = document.getElementById('side-menu');
  const overlay = document.getElementById('menu-overlay');
  const isOpen = !menu.classList.contains('hidden');
  const nextOpen = typeof forceState === 'boolean' ? forceState : !isOpen;
  menu.classList.toggle('hidden', !nextOpen);
  overlay.classList.toggle('hidden', !nextOpen);
}

function menuNavigate(viewName) {
  toggleMenu(false);
  if (viewName === 'create-invoice') {
    openCreateInvoice();
  } else {
    switchView(viewName);
  }
}

const SUB_VIEW_PARENTS = {
  'customer-form': 'customers',
  'customer-detail': 'customers',
  'supplier-form': 'suppliers',
  'supplier-detail': 'suppliers',
  'inventory-form': 'inventory',
  'create-invoice': 'invoices',
  'thermal-preview': 'create-invoice',
  'suppliers': 'dashboard',
  'settings': 'dashboard',
  'about': 'dashboard'
};

function switchView(viewName) {
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
  document.querySelectorAll('.bottom-nav button').forEach(b => b.classList.remove('active'));

  const viewEl = document.getElementById(`view-${viewName}`);
  if (viewEl) viewEl.classList.add('active');

  const navEl = document.getElementById(`nav-${viewName}`);
  if (navEl) navEl.classList.add('active');

  const backBtn = document.getElementById('header-back-btn');
  if (backBtn) {
    backBtn.classList.toggle('hidden', !SUB_VIEW_PARENTS[viewName]);
  }

  currentView = viewName;
  const scroll = document.getElementById('main-scroll');
  if (scroll) scroll.scrollTop = 0;

  loadViewData(viewName);
}

function handleBackAction() {
  const parent = SUB_VIEW_PARENTS[currentView];
  if (parent) {
    switchView(parent);
  } else if (currentView !== 'dashboard') {
    switchView('dashboard');
  }
}

window.handleAndroidBack = function () {
  const menu = document.getElementById('side-menu');
  if (menu && !menu.classList.contains('hidden')) {
    toggleMenu(false);
    return 'HANDLED';
  }
  const parent = SUB_VIEW_PARENTS[currentView];
  if (parent) {
    switchView(parent);
    return 'HANDLED';
  }
  if (currentView !== 'dashboard') {
    switchView('dashboard');
    return 'HANDLED';
  }
  return 'EXIT';
};

function loadViewData(view) {
  if (!window.Android) return;
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
    loadReports();
  } else if (view === 'settings') {
    window.Android.getSettings();
    window.Android.getBluetoothDevices();
  }
}

// ---------------- Dashboard ----------------

window.onDashboardData = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    appCurrency = data.currency || appCurrency;
    storeName = data.storeName || storeName;
    document.getElementById('app-title').textContent = storeName;
    document.getElementById('drawer-store-name').textContent = storeName;

    document.getElementById('dash-sales').textContent = `${fmtMoney(data.todaySales ?? data.sales)} ${appCurrency}`;
    document.getElementById('dash-debts').textContent = `${fmtMoney(data.debts)} ${appCurrency}`;
    document.getElementById('dash-payables').textContent = `${fmtMoney(data.payables)} ${appCurrency}`;
    document.getElementById('dash-inventory').textContent = `${fmtMoney(data.inventoryVal)} ${appCurrency}`;

    const lowBox = document.getElementById('dash-low-stock-alert');
    if ((data.lowStockCount || 0) > 0) {
      lowBox.textContent = `تنبيه المخزون: يوجد ${data.lowStockCount} أصناف كميتها منخفضة (≤ 10) — اضغط للعرض`;
      lowBox.classList.remove('hidden');
    } else {
      lowBox.classList.add('hidden');
    }

    const recentEl = document.getElementById('dash-recent-invoices');
    const recent = data.recentInvoices || [];
    if (recent.length === 0) {
      recentEl.innerHTML = '<div class="empty-state">لا توجد فواتير مسجلة بعد</div>';
    } else {
      recentEl.innerHTML = recent.map(inv => {
        const badge = inv.status === 'PAID'
          ? '<span class="badge badge-green">مدفوع</span>'
          : (inv.status === 'PARTIAL' ? '<span class="badge badge-amber">جزئي</span>' : '<span class="badge badge-red">آجل</span>');
        return `
          <div class="list-item" onclick="openEditInvoice(${inv.id})">
            <div>
              <strong>#${escapeHtml(inv.invoiceNumber)}</strong> ${badge}
              <div class="muted-text">${escapeHtml(inv.partyName)} • ${escapeHtml(invoiceTypeAr(inv.type))}</div>
            </div>
            <div class="list-item-actions">
              <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
              <span class="muted-text" style="font-size:11px;">${fmtDate(inv.date)}</span>
            </div>
          </div>
        `;
      }).join('');
    }
  } catch (e) {
    console.error(e);
  }
};

function openInventoryLowStock() {
  inventoryFilter = 'LOW';
  document.querySelectorAll('#inventory-filter-tabs .pill').forEach((p, idx) => {
    p.classList.toggle('active', idx === 1);
  });
  switchView('inventory');
}

// ---------------- Customers ----------------

window.onCustomersData = function (jsonStr) {
  try {
    customersData = JSON.parse(jsonStr);
    if (currentView === 'customers') {
      renderCustomersList();
    }
    if (currentView === 'create-invoice') {
      populatePartyDropdown();
    }
  } catch (e) {
    console.error(e);
  }
};

function setCustomerFilter(filter, btn) {
  customerFilter = filter;
  document.querySelectorAll('#customer-filter-tabs .pill').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  renderCustomersList();
}

function filterCustomers() {
  renderCustomersList();
}

function renderCustomersList() {
  const container = document.getElementById('customers-list');
  const summary = document.getElementById('customers-summary-strip');
  const query = (document.getElementById('customer-search').value || '').trim().toLowerCase();

  const filtered = customersData.filter(c => {
    const matchSearch = !query || c.name.toLowerCase().includes(query) || (c.phone || '').includes(query);
    if (!matchSearch) return false;
    if (customerFilter === 'DEBIT') return c.balance > 0.01;
    if (customerFilter === 'CREDIT') return c.balance < -0.01;
    if (customerFilter === 'SETTLED') return Math.abs(c.balance) <= 0.01;
    return true;
  });

  const totalDebt = filtered.reduce((sum, c) => sum + Math.max(c.balance, 0), 0);
  summary.innerHTML = `<span>عدد العملاء: ${filtered.length}</span><span>إجمالي الديون: ${fmtMoney(totalDebt)} ${escapeHtml(appCurrency)}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا يوجد عملاء مطابقون للبحث</div>';
    return;
  }

  container.innerHTML = filtered.map(c => {
    const isDebit = c.balance > 0.01;
    const isCredit = c.balance < -0.01;
    const cls = isDebit ? 'text-danger' : (isCredit ? 'text-success' : 'text-dark');
    const label = isDebit ? `عليه ${fmtMoney(c.balance)}` : (isCredit ? `له ${fmtMoney(-c.balance)}` : 'مسدد 0');
    return `
      <div class="list-item" onclick="openCustomerDetail(${c.id})">
        <div>
          <strong>${escapeHtml(c.name)}</strong>
          <div class="muted-text">${escapeHtml(c.phone || 'بدون جوال')}${c.address ? ' • ' + escapeHtml(c.address) : ''}</div>
        </div>
        <div class="list-item-actions">
          <span class="${cls}" style="font-weight:800; font-size:14px;">${label} ${escapeHtml(appCurrency)}</span>
          <span class="badge badge-blue">كشف الحساب</span>
        </div>
      </div>
    `;
  }).join('');
}

function openCustomerForm(customerId) {
  editingCustomerId = customerId || 0;
  const isEdit = editingCustomerId > 0;
  document.getElementById('customer-form-title').textContent = isEdit ? 'تعديل بيانات العميل' : 'إضافة عميل جديد';
  document.getElementById('cust-form-balance-group').classList.toggle('hidden', isEdit);

  if (isEdit && activeCustomerDetail && activeCustomerDetail.customer.id === editingCustomerId) {
    const c = activeCustomerDetail.customer;
    document.getElementById('cust-form-name').value = c.name || '';
    document.getElementById('cust-form-phone').value = c.phone || '';
    document.getElementById('cust-form-address').value = c.address || '';
    document.getElementById('cust-form-balance').value = c.balance || 0;
  } else {
    document.getElementById('cust-form-name').value = '';
    document.getElementById('cust-form-phone').value = '';
    document.getElementById('cust-form-address').value = '';
    document.getElementById('cust-form-balance').value = '0';
  }
  switchView('customer-form');
}

function submitCustomerForm() {
  const name = (document.getElementById('cust-form-name').value || '').trim();
  const phone = (document.getElementById('cust-form-phone').value || '').trim();
  const address = (document.getElementById('cust-form-address').value || '').trim();
  const balance = parseFloat(document.getElementById('cust-form-balance').value) || 0;

  if (!name) {
    showToast('الرجاء إدخال اسم العميل', 'error');
    return;
  }
  if (!window.Android) return;

  const payload = { id: editingCustomerId, name, phone, address, balance };
  const reqId = nextReqId((data) => {
    if (editingCustomerId > 0) {
      openCustomerDetail(editingCustomerId);
    } else if (data && data.id) {
      openCustomerDetail(data.id);
    } else {
      switchView('customers');
    }
  });
  window.Android.saveCustomer(JSON.stringify(payload), reqId);
}

function openCustomerDetail(customerId) {
  activeCustomerId = customerId;
  editingCustomerTxId = 0;
  pendingDeleteCustomerTxId = 0;
  document.getElementById('cust-tx-form-title').textContent = 'تسجيل حركة حساب جديدة';
  document.getElementById('cust-tx-cancel-edit').classList.add('hidden');
  document.getElementById('customer-delete-confirm').classList.add('hidden');
  document.getElementById('tx-amount').value = '';
  document.getElementById('tx-desc').value = '';
  switchView('customer-detail');
  if (window.Android) {
    window.Android.getCustomerDetail(customerId);
  }
}

window.onCustomerDetailData = function (jsonStr) {
  try {
    activeCustomerDetail = JSON.parse(jsonStr);
    const c = activeCustomerDetail.customer;
    const isDebit = c.balance > 0.01;
    const isCredit = c.balance < -0.01;
    const cls = isDebit ? 'text-danger' : (isCredit ? 'text-success' : 'text-dark');
    const balDesc = isDebit
      ? `عليه ${fmtMoney(c.balance)} ${appCurrency}`
      : (isCredit ? `له ${fmtMoney(-c.balance)} ${appCurrency}` : `مسدد بالكامل (0 ${appCurrency})`);

    document.getElementById('customer-info-card').innerHTML = `
      <div class="card-title-row">
        <div>
          <h2 style="font-size:18px; color:var(--dark-header);">${escapeHtml(c.name)}</h2>
          <div class="muted-text">${escapeHtml(c.phone || 'بدون جوال')}${c.address ? ' • ' + escapeHtml(c.address) : ''}</div>
        </div>
        <div class="btn-row-compact">
          <button type="button" class="tbl-action-btn" onclick="openCustomerForm(${c.id})">تعديل</button>
          <button type="button" class="tbl-action-btn del" onclick="toggleCustomerDeleteConfirm(true)">حذف</button>
        </div>
      </div>
      <div class="info-pill">الرصيد التراكمي الحالي: <span class="${cls}" style="font-weight:800;">${escapeHtml(balDesc)}</span></div>
    `;

    renderCustomerTransactions();
    renderCustomerInvoices();
  } catch (e) {
    console.error(e);
  }
};

function toggleCustomerDeleteConfirm(show) {
  document.getElementById('customer-delete-confirm').classList.toggle('hidden', !show);
}

function confirmDeleteCustomer() {
  if (!window.Android || !activeCustomerId) return;
  const reqId = nextReqId(() => {
    switchView('customers');
  });
  window.Android.deleteCustomer(activeCustomerId, reqId);
}

function renderCustomerTransactions() {
  if (!activeCustomerDetail) return;
  const tbody = document.getElementById('customer-tx-tbody');
  const query = (document.getElementById('cust-tx-search').value || '').trim().toLowerCase();
  const typeFilter = document.getElementById('cust-tx-type-filter').value;

  const list = (activeCustomerDetail.transactions || []).filter(t => {
    const matchText = !query || (t.description || '').toLowerCase().includes(query);
    if (!matchText) return false;
    if (typeFilter === 'DEBIT') return t.isDebit;
    if (typeFilter === 'CREDIT') return !t.isDebit;
    return true;
  });

  if (list.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--text-secondary);">لا توجد حركات مسجلة</td></tr>';
    return;
  }

  tbody.innerHTML = list.map(t => {
    const cls = t.isDebit ? 'text-danger' : 'text-success';
    const signLabel = t.isDebit ? 'عليه' : 'له';
    const isConfirming = pendingDeleteCustomerTxId === t.id;
    const actionHtml = isConfirming
      ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteCustomerTx(${t.id})">تأكيد</button>
         <button type="button" class="tbl-action-btn" onclick="askDeleteCustomerTx(0)">لا</button>`
      : `<button type="button" class="tbl-action-btn" onclick="startEditCustomerTx(${t.id})">تعديل</button>
         <button type="button" class="tbl-action-btn del" onclick="askDeleteCustomerTx(${t.id})">حذف</button>`;
    return `
      <tr>
        <td style="font-size:11px; white-space:nowrap;">${fmtDate(t.date)}</td>
        <td>${escapeHtml(t.description)}</td>
        <td class="${cls}" style="font-weight:700; white-space:nowrap;">${signLabel} ${fmtMoney(t.amount)}</td>
        <td style="font-weight:700; white-space:nowrap;">${fmtMoney(t.runningBalance)}</td>
        <td style="white-space:nowrap;">${actionHtml}</td>
      </tr>
    `;
  }).join('');
}

function renderCustomerInvoices() {
  if (!activeCustomerDetail) return;
  const container = document.getElementById('customer-invoices-list');
  const invoices = activeCustomerDetail.invoices || [];
  if (invoices.length === 0) {
    container.innerHTML = '<div class="muted-text">لا توجد فواتير مرتبطة بهذا العميل</div>';
    return;
  }
  container.innerHTML = invoices.map(inv => `
    <div class="list-item" onclick="openEditInvoice(${inv.id})">
      <div>
        <strong>#${escapeHtml(inv.invoiceNumber)}</strong>
        <div class="muted-text">${escapeHtml(invoiceTypeAr(inv.type))} • ${fmtDate(inv.date)}</div>
      </div>
      <div class="list-item-actions">
        <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
        <span class="muted-text">متبقي: ${fmtMoney(inv.remainingAmount)}</span>
      </div>
    </div>
  `).join('');
}

function startEditCustomerTx(txId) {
  if (!activeCustomerDetail) return;
  const tx = (activeCustomerDetail.transactions || []).find(t => t.id === txId);
  if (!tx) return;
  editingCustomerTxId = txId;
  document.getElementById('cust-tx-form-title').textContent = 'تعديل الحركة المحددة (اختر عليه أو له للحفظ)';
  document.getElementById('tx-amount').value = tx.amount;
  document.getElementById('tx-desc').value = tx.description;
  document.getElementById('cust-tx-cancel-edit').classList.remove('hidden');
}

function cancelEditCustomerTx() {
  editingCustomerTxId = 0;
  document.getElementById('cust-tx-form-title').textContent = 'تسجيل حركة حساب جديدة';
  document.getElementById('tx-amount').value = '';
  document.getElementById('tx-desc').value = '';
  document.getElementById('cust-tx-cancel-edit').classList.add('hidden');
}

function askDeleteCustomerTx(txId) {
  pendingDeleteCustomerTxId = txId;
  renderCustomerTransactions();
}

function confirmDeleteCustomerTx(txId) {
  if (!window.Android || !activeCustomerId) return;
  const reqId = nextReqId(() => {
    pendingDeleteCustomerTxId = 0;
  });
  window.Android.deleteCustomerTransaction(txId, activeCustomerId, reqId);
}

function submitCustomerTx(isDebit) {
  const amount = parseFloat(document.getElementById('tx-amount').value);
  const desc = (document.getElementById('tx-desc').value || '').trim();
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast('الرجاء إدخال مبلغ صحيح أكبر من صفر', 'error');
    return;
  }
  if (!window.Android || !activeCustomerId) return;

  const reqId = nextReqId(() => {
    cancelEditCustomerTx();
  });
  if (editingCustomerTxId > 0) {
    window.Android.updateCustomerTransaction(editingCustomerTxId, activeCustomerId, amount, isDebit, desc, reqId);
  } else {
    window.Android.addCustomerTransaction(activeCustomerId, amount, isDebit, desc, reqId);
  }
}

function shareCustomerWhatsApp() {
  if (!window.Android || !activeCustomerId) return;
  window.Android.shareCustomerWhatsApp(activeCustomerId, nextReqId());
}

// ---------------- Suppliers ----------------

window.onSuppliersData = function (jsonStr) {
  try {
    suppliersData = JSON.parse(jsonStr);
    if (currentView === 'suppliers') {
      renderSuppliersList();
    }
    if (currentView === 'create-invoice') {
      populatePartyDropdown();
    }
  } catch (e) {
    console.error(e);
  }
};

function filterSuppliers() {
  renderSuppliersList();
}

function renderSuppliersList() {
  const container = document.getElementById('suppliers-list');
  const summary = document.getElementById('suppliers-summary-strip');
  const query = (document.getElementById('supplier-search').value || '').trim().toLowerCase();

  const filtered = suppliersData.filter(s =>
    !query ||
    s.name.toLowerCase().includes(query) ||
    (s.company || '').toLowerCase().includes(query) ||
    (s.phone || '').includes(query)
  );

  const totalPayable = filtered.reduce((sum, s) => sum + Math.max(s.balance, 0), 0);
  summary.innerHTML = `<span>عدد الموردين: ${filtered.length}</span><span>إجمالي المستحقات: ${fmtMoney(totalPayable)} ${escapeHtml(appCurrency)}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا يوجد موردون مسجلون</div>';
    return;
  }

  container.innerHTML = filtered.map(s => {
    const cls = s.balance > 0.01 ? 'text-danger' : (s.balance < -0.01 ? 'text-success' : 'text-dark');
    const balLabel = s.balance > 0.01 ? `مستحق له ${fmtMoney(s.balance)}` : (s.balance < -0.01 ? `رصيد لنا ${fmtMoney(-s.balance)}` : 'مسدد 0');
    return `
      <div class="list-item" onclick="openSupplierDetail(${s.id})">
        <div>
          <strong>${escapeHtml(s.name)}</strong>
          <div class="muted-text">${escapeHtml(s.company || 'مورد')}${s.phone ? ' • ' + escapeHtml(s.phone) : ''}</div>
        </div>
        <div class="list-item-actions">
          <span class="${cls}" style="font-weight:800; font-size:14px;">${balLabel} ${escapeHtml(appCurrency)}</span>
          <span class="badge badge-blue">كشف المورد</span>
        </div>
      </div>
    `;
  }).join('');
}

function openSupplierForm(supplierId) {
  editingSupplierId = supplierId || 0;
  const isEdit = editingSupplierId > 0;
  document.getElementById('supplier-form-title').textContent = isEdit ? 'تعديل بيانات المورد' : 'إضافة مورد جديد';
  document.getElementById('supp-form-balance-group').classList.toggle('hidden', isEdit);

  if (isEdit && activeSupplierDetail && activeSupplierDetail.supplier.id === editingSupplierId) {
    const s = activeSupplierDetail.supplier;
    document.getElementById('supp-form-name').value = s.name || '';
    document.getElementById('supp-form-company').value = s.company || '';
    document.getElementById('supp-form-phone').value = s.phone || '';
    document.getElementById('supp-form-balance').value = s.balance || 0;
  } else {
    document.getElementById('supp-form-name').value = '';
    document.getElementById('supp-form-company').value = '';
    document.getElementById('supp-form-phone').value = '';
    document.getElementById('supp-form-balance').value = '0';
  }
  switchView('supplier-form');
}

function submitSupplierForm() {
  const name = (document.getElementById('supp-form-name').value || '').trim();
  const company = (document.getElementById('supp-form-company').value || '').trim();
  const phone = (document.getElementById('supp-form-phone').value || '').trim();
  const balance = parseFloat(document.getElementById('supp-form-balance').value) || 0;

  if (!name) {
    showToast('الرجاء إدخال اسم المورد', 'error');
    return;
  }
  if (!window.Android) return;

  const payload = { id: editingSupplierId, name, company, phone, balance };
  const reqId = nextReqId((data) => {
    if (editingSupplierId > 0) {
      openSupplierDetail(editingSupplierId);
    } else if (data && data.id) {
      openSupplierDetail(data.id);
    } else {
      switchView('suppliers');
    }
  });
  window.Android.saveSupplier(JSON.stringify(payload), reqId);
}

function openSupplierDetail(supplierId) {
  activeSupplierId = supplierId;
  editingSupplierTxId = 0;
  pendingDeleteSupplierTxId = 0;
  document.getElementById('supp-tx-form-title').textContent = 'تسجيل حركة حساب مورد';
  document.getElementById('supp-tx-cancel-edit').classList.add('hidden');
  document.getElementById('supplier-delete-confirm').classList.add('hidden');
  document.getElementById('supp-tx-amount').value = '';
  document.getElementById('supp-tx-desc').value = '';
  switchView('supplier-detail');
  if (window.Android) {
    window.Android.getSupplierDetail(supplierId);
  }
}

window.onSupplierDetailData = function (jsonStr) {
  try {
    activeSupplierDetail = JSON.parse(jsonStr);
    const s = activeSupplierDetail.supplier;
    const cls = s.balance > 0.01 ? 'text-danger' : (s.balance < -0.01 ? 'text-success' : 'text-dark');
    const balDesc = s.balance > 0.01
      ? `مستحق للمورد ${fmtMoney(s.balance)} ${appCurrency}`
      : (s.balance < -0.01 ? `رصيد لنا ${fmtMoney(-s.balance)} ${appCurrency}` : `الحساب مصفر (0 ${appCurrency})`);

    document.getElementById('supplier-info-card').innerHTML = `
      <div class="card-title-row">
        <div>
          <h2 style="font-size:18px; color:var(--dark-header);">${escapeHtml(s.name)}</h2>
          <div class="muted-text">${escapeHtml(s.company || 'مورد')}${s.phone ? ' • ' + escapeHtml(s.phone) : ''}</div>
        </div>
        <div class="btn-row-compact">
          <button type="button" class="tbl-action-btn" onclick="openSupplierForm(${s.id})">تعديل</button>
          <button type="button" class="tbl-action-btn del" onclick="toggleSupplierDeleteConfirm(true)">حذف</button>
        </div>
      </div>
      <div class="info-pill">الرصيد الحالي: <span class="${cls}" style="font-weight:800;">${escapeHtml(balDesc)}</span></div>
    `;

    renderSupplierTransactions();
    renderSupplierInvoices();
  } catch (e) {
    console.error(e);
  }
};

function toggleSupplierDeleteConfirm(show) {
  document.getElementById('supplier-delete-confirm').classList.toggle('hidden', !show);
}

function confirmDeleteSupplier() {
  if (!window.Android || !activeSupplierId) return;
  const reqId = nextReqId(() => switchView('suppliers'));
  window.Android.deleteSupplier(activeSupplierId, reqId);
}

function renderSupplierTransactions() {
  if (!activeSupplierDetail) return;
  const tbody = document.getElementById('supplier-tx-tbody');
  const list = activeSupplierDetail.transactions || [];
  if (list.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--text-secondary);">لا توجد حركات مسجلة</td></tr>';
    return;
  }
  tbody.innerHTML = list.map(t => {
    const cls = t.isCredit ? 'text-danger' : 'text-success';
    const label = t.isCredit ? 'له (مستحق)' : 'عليه (سداد)';
    const isConfirming = pendingDeleteSupplierTxId === t.id;
    const actionHtml = isConfirming
      ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteSupplierTx(${t.id})">تأكيد</button>
         <button type="button" class="tbl-action-btn" onclick="askDeleteSupplierTx(0)">لا</button>`
      : `<button type="button" class="tbl-action-btn" onclick="startEditSupplierTx(${t.id})">تعديل</button>
         <button type="button" class="tbl-action-btn del" onclick="askDeleteSupplierTx(${t.id})">حذف</button>`;
    return `
      <tr>
        <td style="font-size:11px; white-space:nowrap;">${fmtDate(t.date)}</td>
        <td>${escapeHtml(t.description)}</td>
        <td class="${cls}" style="font-weight:700; white-space:nowrap;">${label} ${fmtMoney(t.amount)}</td>
        <td style="font-weight:700; white-space:nowrap;">${fmtMoney(t.runningBalance)}</td>
        <td style="white-space:nowrap;">${actionHtml}</td>
      </tr>
    `;
  }).join('');
}

function renderSupplierInvoices() {
  if (!activeSupplierDetail) return;
  const container = document.getElementById('supplier-invoices-list');
  const list = activeSupplierDetail.invoices || [];
  if (list.length === 0) {
    container.innerHTML = '<div class="muted-text">لا توجد فواتير مشتريات لهذا المورد</div>';
    return;
  }
  container.innerHTML = list.map(inv => `
    <div class="list-item" onclick="openEditInvoice(${inv.id})">
      <div>
        <strong>#${escapeHtml(inv.invoiceNumber)}</strong>
        <div class="muted-text">${fmtDate(inv.date)}</div>
      </div>
      <div class="list-item-actions">
        <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
        <span class="muted-text">متبقي: ${fmtMoney(inv.remainingAmount)}</span>
      </div>
    </div>
  `).join('');
}

function startEditSupplierTx(txId) {
  if (!activeSupplierDetail) return;
  const tx = (activeSupplierDetail.transactions || []).find(t => t.id === txId);
  if (!tx) return;
  editingSupplierTxId = txId;
  document.getElementById('supp-tx-form-title').textContent = 'تعديل حركة المورد';
  document.getElementById('supp-tx-amount').value = tx.amount;
  document.getElementById('supp-tx-desc').value = tx.description;
  document.getElementById('supp-tx-cancel-edit').classList.remove('hidden');
}

function cancelEditSupplierTx() {
  editingSupplierTxId = 0;
  document.getElementById('supp-tx-form-title').textContent = 'تسجيل حركة حساب مورد';
  document.getElementById('supp-tx-amount').value = '';
  document.getElementById('supp-tx-desc').value = '';
  document.getElementById('supp-tx-cancel-edit').classList.add('hidden');
}

function askDeleteSupplierTx(txId) {
  pendingDeleteSupplierTxId = txId;
  renderSupplierTransactions();
}

function confirmDeleteSupplierTx(txId) {
  if (!window.Android || !activeSupplierId) return;
  const reqId = nextReqId(() => { pendingDeleteSupplierTxId = 0; });
  window.Android.deleteSupplierTransaction(txId, activeSupplierId, reqId);
}

function submitSupplierTx(isCredit) {
  const amount = parseFloat(document.getElementById('supp-tx-amount').value);
  const desc = (document.getElementById('supp-tx-desc').value || '').trim();
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast('الرجاء إدخال مبلغ صحيح أكبر من صفر', 'error');
    return;
  }
  if (!window.Android || !activeSupplierId) return;
  const reqId = nextReqId(() => cancelEditSupplierTx());
  if (editingSupplierTxId > 0) {
    window.Android.updateSupplierTransaction(editingSupplierTxId, activeSupplierId, amount, isCredit, desc, reqId);
  } else {
    window.Android.addSupplierTransaction(activeSupplierId, amount, isCredit, desc, reqId);
  }
}

function shareSupplierWhatsApp() {
  if (!window.Android || !activeSupplierId) return;
  window.Android.shareSupplierWhatsApp(activeSupplierId, nextReqId());
}

// ---------------- Inventory ----------------

window.onInventoryData = function (jsonStr) {
  try {
    inventoryData = JSON.parse(jsonStr);
    if (currentView === 'inventory') {
      renderInventoryList();
    }
    if (currentView === 'create-invoice') {
      populateItemDropdown();
    }
  } catch (e) {
    console.error(e);
  }
};

function setInventoryFilter(filter, btn) {
  inventoryFilter = filter;
  document.querySelectorAll('#inventory-filter-tabs .pill').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  renderInventoryList();
}

function filterInventory() {
  renderInventoryList();
}

function renderInventoryList() {
  const container = document.getElementById('inventory-list');
  const summary = document.getElementById('inventory-summary-strip');
  const query = (document.getElementById('inventory-search').value || '').trim().toLowerCase();

  const filtered = inventoryData.filter(i => {
    const matchText = !query || i.name.toLowerCase().includes(query) || (i.category || '').toLowerCase().includes(query);
    if (!matchText) return false;
    if (inventoryFilter === 'LOW') return i.quantity <= 10;
    if (inventoryFilter === 'OUT') return i.quantity <= 0;
    return true;
  });

  const totalCost = filtered.reduce((sum, i) => sum + (i.quantity * i.purchasePrice), 0);
  summary.innerHTML = `<span>عدد الأصناف: ${filtered.length}</span><span>القيمة بالتكلفة: ${fmtMoney(totalCost)} ${escapeHtml(appCurrency)}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا توجد أصناف مطابقة</div>';
    return;
  }

  container.innerHTML = filtered.map(i => {
    const badge = i.quantity <= 0
      ? '<span class="badge badge-red">نافذ</span>'
      : (i.quantity <= 10 ? '<span class="badge badge-amber">مخزون منخفض</span>' : '<span class="badge badge-green">متوفر</span>');
    const isConfirming = pendingDeleteInventoryId === i.id;
    const actionsHtml = isConfirming
      ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteInventory(${i.id})">تأكيد الحذف</button>
         <button type="button" class="tbl-action-btn" onclick="askDeleteInventory(0)">إلغاء</button>`
      : `<button type="button" class="tbl-action-btn" onclick="openInventoryForm(${i.id})">تعديل</button>
         <button type="button" class="tbl-action-btn del" onclick="askDeleteInventory(${i.id})">حذف</button>`;

    return `
      <div class="card compact-card">
        <div class="card-title-row">
          <div>
            <strong>${escapeHtml(i.name)}</strong> ${badge}
            <div class="muted-text">${escapeHtml(i.category || 'عام')} • الوحدة: ${escapeHtml(i.unit || 'حبة')}</div>
          </div>
          <strong style="font-size:15px;">الكمية: ${i.quantity}</strong>
        </div>
        <div class="card-title-row" style="margin-bottom:0; margin-top:6px;">
          <span class="muted-text">شراء: ${fmtMoney(i.purchasePrice)} | بيع: ${fmtMoney(i.sellingPrice)} ${escapeHtml(appCurrency)}</span>
          <div class="btn-row-compact">${actionsHtml}</div>
        </div>
      </div>
    `;
  }).join('');
}

function openInventoryForm(itemId) {
  editingInventoryId = itemId || 0;
  const isEdit = editingInventoryId > 0;
  document.getElementById('inventory-form-title').textContent = isEdit ? 'تعديل بيانات الصنف' : 'إضافة صنف جديد للمخزن';

  if (isEdit) {
    const item = inventoryData.find(x => x.id === editingInventoryId);
    if (item) {
      document.getElementById('inv-item-form-name').value = item.name || '';
      document.getElementById('inv-item-form-category').value = item.category || 'مواد غذائية';
      document.getElementById('inv-item-form-unit').value = item.unit || 'حبة';
      document.getElementById('inv-item-form-qty').value = item.quantity;
      document.getElementById('inv-item-form-buy').value = item.purchasePrice;
      document.getElementById('inv-item-form-sell').value = item.sellingPrice;
    }
  } else {
    document.getElementById('inv-item-form-name').value = '';
    document.getElementById('inv-item-form-category').value = 'مواد غذائية';
    document.getElementById('inv-item-form-unit').value = 'حبة';
    document.getElementById('inv-item-form-qty').value = '0';
    document.getElementById('inv-item-form-buy').value = '0';
    document.getElementById('inv-item-form-sell').value = '0';
  }
  updateMarginPreview();
  switchView('inventory-form');
}

function updateMarginPreview() {
  const buy = parseFloat(document.getElementById('inv-item-form-buy').value) || 0;
  const sell = parseFloat(document.getElementById('inv-item-form-sell').value) || 0;
  const diff = sell - buy;
  document.getElementById('inventory-margin-preview').textContent =
    `الربح المتوقع للوحدة: ${fmtMoney(diff)} ${appCurrency}`;
}

function submitInventoryForm() {
  const name = (document.getElementById('inv-item-form-name').value || '').trim();
  const category = (document.getElementById('inv-item-form-category').value || '').trim();
  const unit = (document.getElementById('inv-item-form-unit').value || '').trim();
  const quantity = parseInt(document.getElementById('inv-item-form-qty').value, 10);
  const purchasePrice = parseFloat(document.getElementById('inv-item-form-buy').value);
  const sellingPrice = parseFloat(document.getElementById('inv-item-form-sell').value);

  if (!name) {
    showToast('الرجاء إدخال اسم الصنف', 'error');
    return;
  }
  if (!Number.isFinite(quantity) || quantity < 0 || !Number.isFinite(purchasePrice) || purchasePrice < 0 || !Number.isFinite(sellingPrice) || sellingPrice < 0) {
    showToast('تأكد من صحة الكمية والأسعار', 'error');
    return;
  }
  if (!window.Android) return;

  const payload = { id: editingInventoryId, name, category, unit, quantity, purchasePrice, sellingPrice };
  const reqId = nextReqId(() => switchView('inventory'));
  window.Android.saveInventoryItem(JSON.stringify(payload), reqId);
}

function askDeleteInventory(itemId) {
  pendingDeleteInventoryId = itemId;
  renderInventoryList();
}

function confirmDeleteInventory(itemId) {
  if (!window.Android) return;
  const reqId = nextReqId(() => { pendingDeleteInventoryId = 0; });
  window.Android.deleteInventoryItem(itemId, reqId);
}

// ---------------- Invoices List ----------------

window.onInvoicesData = function (jsonStr) {
  try {
    invoicesData = JSON.parse(jsonStr);
    if (currentView === 'invoices') {
      renderInvoicesList();
    }
  } catch (e) {
    console.error(e);
  }
};

function setInvoiceFilter(filter, btn) {
  invoiceFilter = filter;
  document.querySelectorAll('#invoice-filter-tabs .pill').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  renderInvoicesList();
}

function renderInvoicesList() {
  const container = document.getElementById('invoices-list');
  const summary = document.getElementById('invoices-summary-strip');
  const query = (document.getElementById('invoices-search').value || '').trim().toLowerCase();

  const filtered = invoicesData.filter(inv => {
    const matchText = !query ||
      (inv.invoiceNumber || '').toLowerCase().includes(query) ||
      (inv.partyName || '').toLowerCase().includes(query);
    if (!matchText) return false;
    if (invoiceFilter === 'CREDIT') return inv.type === 'CREDIT';
    if (invoiceFilter === 'PURCHASE') return inv.type === 'PURCHASE';
    if (invoiceFilter === 'CASH') return inv.type === 'CASH' || inv.type === 'SALE';
    if (invoiceFilter === 'UNPAID') return inv.status !== 'PAID';
    return true;
  });

  const totalSum = filtered.reduce((acc, inv) => acc + (inv.totalAmount || 0), 0);
  const totalRem = filtered.reduce((acc, inv) => acc + (inv.remainingAmount || 0), 0);
  summary.innerHTML = `<span>الفواتير: ${filtered.length}</span><span>الإجمالي: ${fmtMoney(totalSum)} | المتبقي: ${fmtMoney(totalRem)} ${escapeHtml(appCurrency)}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا توجد فواتير مطابقة</div>';
    return;
  }

  container.innerHTML = filtered.map(inv => {
    const statusBadge = inv.status === 'PAID'
      ? '<span class="badge badge-green">مدفوع</span>'
      : (inv.status === 'PARTIAL' ? '<span class="badge badge-amber">مدفوع جزئياً</span>' : '<span class="badge badge-red">آجل</span>');
    const isConfirming = pendingDeleteInvoiceId === inv.id;
    const footerActions = isConfirming
      ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteInvoice(${inv.id})">تأكيد الحذف</button>
         <button type="button" class="tbl-action-btn" onclick="askDeleteInvoice(0)">إلغاء</button>`
      : `<button type="button" class="tbl-action-btn" onclick="openEditInvoice(${inv.id})">تعديل</button>
         <button type="button" class="tbl-action-btn" onclick="quickThermalPreview(${inv.id})">معاينة</button>
         <button type="button" class="tbl-action-btn" onclick="quickShareImage(${inv.id})">واتساب</button>
         <button type="button" class="tbl-action-btn" onclick="quickOpenPdf(${inv.id})">PDF</button>
         <button type="button" class="tbl-action-btn del" onclick="askDeleteInvoice(${inv.id})">حذف</button>`;

    return `
      <div class="card compact-card">
        <div class="card-title-row">
          <div>
            <strong>فاتورة #${escapeHtml(inv.invoiceNumber)}</strong> ${statusBadge}
            <div class="muted-text">${escapeHtml(inv.partyName)} • ${escapeHtml(invoiceTypeAr(inv.type))} • ${fmtDate(inv.date)}</div>
          </div>
          <div style="text-align:left;">
            <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
            <div class="muted-text" style="font-size:11px;">متبقي: ${fmtMoney(inv.remainingAmount)}</div>
          </div>
        </div>
        <div class="btn-row-compact" style="margin-top:6px;">${footerActions}</div>
      </div>
    `;
  }).join('');
}

function askDeleteInvoice(id) {
  pendingDeleteInvoiceId = id;
  renderInvoicesList();
}

function confirmDeleteInvoice(id) {
  if (!window.Android) return;
  const reqId = nextReqId(() => { pendingDeleteInvoiceId = 0; });
  window.Android.deleteInvoice(id, reqId);
}

function quickThermalPreview(id) {
  if (!window.Android) return;
  activePreviewInvoiceId = id;
  window.Android.previewThermalInvoice(id, nextReqId());
}

function quickShareImage(id) {
  if (!window.Android) return;
  window.Android.shareInvoiceImage(id, nextReqId());
}

function quickOpenPdf(id) {
  if (!window.Android) return;
  window.Android.generateAndOpenPdf(id, nextReqId());
}

// ---------------- Create / Edit Invoice ----------------

function openCreateInvoiceForParty(type, partyId) {
  openCreateInvoice(type, partyId);
}

function openCreateInvoice(presetType, presetPartyId) {
  editingInvoiceId = 0;
  invoiceDirty = true;
  cartItems = [];
  document.getElementById('invoice-screen-title').textContent = 'إصدار فاتورة جديدة';
  document.getElementById('btn-save-invoice').textContent = 'حفظ الفاتورة';
  document.getElementById('invoice-saved-badge').classList.add('hidden');
  document.getElementById('inv-type').value = presetType || 'CASH';
  document.getElementById('inv-paid').value = '0';
  document.getElementById('inv-item-qty').value = '1';
  document.getElementById('inv-item-price').value = '';
  document.getElementById('inv-item-total').value = '';
  document.getElementById('quick-add-stock-box').classList.add('hidden');
  document.getElementById('exchange-calc-box').classList.add('hidden');

  if (window.Android) {
    window.Android.getCustomers();
    window.Android.getSuppliers();
    window.Android.getInventory();
    window.Android.getNextInvoiceNumber();
  }

  onInvoiceTypeChange();
  if (presetPartyId) {
    setTimeout(() => {
      const sel = document.getElementById('inv-party');
      if (sel) sel.value = String(presetPartyId);
      updatePartyBalanceChip();
    }, 120);
  }
  renderCart();
  switchView('create-invoice');
}

window.onNextInvoiceNumber = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    if (!editingInvoiceId && data.invoiceNumber) {
      document.getElementById('inv-number').value = data.invoiceNumber;
    }
  } catch (e) {
    console.error(e);
  }
};

function openEditInvoice(invoiceId) {
  editingInvoiceId = invoiceId;
  invoiceDirty = false;
  if (window.Android) {
    window.Android.getCustomers();
    window.Android.getSuppliers();
    window.Android.getInventory();
    window.Android.getInvoiceDetails(invoiceId);
  }
}

window.onInvoiceLoadedForEdit = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const inv = data.invoice;
    editingInvoiceId = inv.id;
    invoiceDirty = false;

    document.getElementById('invoice-screen-title').textContent = 'تعديل الفاتورة';
    document.getElementById('btn-save-invoice').textContent = 'حفظ التعديل';
    document.getElementById('invoice-saved-badge').classList.remove('hidden');
    document.getElementById('inv-number').value = inv.invoiceNumber;
    document.getElementById('inv-type').value = inv.type === 'SALE' ? 'CASH' : inv.type;
    onInvoiceTypeChange();
    document.getElementById('inv-party').value = String(inv.partyId || 0);
    document.getElementById('inv-paid').value = inv.paidAmount;

    cartItems = (data.items || []).map(i => ({
      itemId: i.itemId,
      itemName: i.itemName,
      quantity: i.quantity,
      unitPrice: i.unitPrice,
      totalPrice: i.totalPrice
    }));

    populateItemDropdown();
    renderCart();
    updatePartyBalanceChip();
    if (currentView !== 'create-invoice' && currentView !== 'thermal-preview') {
      switchView('create-invoice');
    }
  } catch (e) {
    console.error(e);
  }
};

function onInvoiceTypeChange() {
  const type = document.getElementById('inv-type').value;
  const label = document.getElementById('party-label');
  if (type === 'PURCHASE') {
    label.textContent = 'المورد *';
    populateSuppliersDropdown();
  } else {
    label.textContent = type === 'CREDIT' ? 'العميل (مطلوب للآجل)' : 'العميل';
    populateCustomersDropdown();
  }
  populateItemDropdown();
  updatePaymentModeButtons();
  updatePartyBalanceChip();
  invoiceDirty = true;
}

function populatePartyDropdown() {
  const type = document.getElementById('inv-type').value;
  const currentVal = document.getElementById('inv-party').value;
  if (type === 'PURCHASE') {
    populateSuppliersDropdown();
  } else {
    populateCustomersDropdown();
  }
  if (currentVal) {
    document.getElementById('inv-party').value = currentVal;
  }
  updatePartyBalanceChip();
}

function populateCustomersDropdown() {
  const select = document.getElementById('inv-party');
  let html = '<option value="0">عميل نقدي (بدون حساب)</option>';
  customersData.forEach(c => {
    html += `<option value="${c.id}">${escapeHtml(c.name)} (${fmtMoney(c.balance)} ${escapeHtml(appCurrency)})</option>`;
  });
  select.innerHTML = html;

  const chipsEl = document.getElementById('inv-quick-party-chips');
  chipsEl.innerHTML = customersData.slice(0, 5).map(c =>
    `<button type="button" class="chip chip-blue" onclick="selectQuickParty(${c.id})">${escapeHtml(c.name)}</button>`
  ).join('');
}

function populateSuppliersDropdown() {
  const select = document.getElementById('inv-party');
  let html = '<option value="0">اختر المورد...</option>';
  suppliersData.forEach(s => {
    html += `<option value="${s.id}">${escapeHtml(s.name)}${s.company ? ' - ' + escapeHtml(s.company) : ''}</option>`;
  });
  select.innerHTML = html;

  const chipsEl = document.getElementById('inv-quick-party-chips');
  chipsEl.innerHTML = suppliersData.slice(0, 5).map(s =>
    `<button type="button" class="chip chip-blue" onclick="selectQuickParty(${s.id})">${escapeHtml(s.name)}</button>`
  ).join('');
}

function selectQuickParty(id) {
  document.getElementById('inv-party').value = String(id);
  invoiceDirty = true;
  updatePartyBalanceChip();
}

function updatePartyBalanceChip() {
  const chip = document.getElementById('inv-party-balance-chip');
  const type = document.getElementById('inv-type').value;
  const partyId = parseInt(document.getElementById('inv-party').value, 10) || 0;
  if (!partyId) {
    chip.classList.add('hidden');
    return;
  }
  if (type === 'PURCHASE') {
    const s = suppliersData.find(x => x.id === partyId);
    if (!s) { chip.classList.add('hidden'); return; }
    chip.textContent = `رصيد المورد الحالي: ${fmtMoney(s.balance)} ${appCurrency}`;
    chip.classList.remove('hidden');
  } else {
    const c = customersData.find(x => x.id === partyId);
    if (!c) { chip.classList.add('hidden'); return; }
    const label = c.balance > 0.01 ? `عليه ${fmtMoney(c.balance)}` : (c.balance < -0.01 ? `له ${fmtMoney(-c.balance)}` : 'مسدد 0');
    chip.textContent = `رصيد العميل التراكمي الحالي: ${label} ${appCurrency}`;
    chip.classList.remove('hidden');
  }
}

function populateItemDropdown() {
  const select = document.getElementById('inv-item-select');
  const prev = select.value;
  const type = document.getElementById('inv-type').value;
  let html = '<option value="">اختر صنفاً من المخزن...</option>';
  inventoryData.forEach(i => {
    const price = type === 'PURCHASE' ? i.purchasePrice : i.sellingPrice;
    html += `<option value="${i.id}" data-price="${price}" data-name="${escapeHtml(i.name)}">${escapeHtml(i.name)} (متوفر: ${i.quantity}) - ${fmtMoney(price)}</option>`;
  });
  select.innerHTML = html;
  if (prev) select.value = prev;
}

function onItemSelect() {
  const select = document.getElementById('inv-item-select');
  const opt = select.options[select.selectedIndex];
  if (opt && opt.dataset.price !== undefined) {
    document.getElementById('inv-item-price').value = opt.dataset.price;
    onQtyOrPriceInput();
  }
}

function onQtyOrPriceInput() {
  const qty = parseInt(document.getElementById('inv-item-qty').value, 10) || 0;
  const price = parseFloat(document.getElementById('inv-item-price').value);
  if (qty > 0 && Number.isFinite(price) && price >= 0) {
    document.getElementById('inv-item-total').value = Math.round(qty * price * 100) / 100;
  }
}

function onTotalDirectInput() {
  const qty = parseInt(document.getElementById('inv-item-qty').value, 10) || 1;
  const total = parseFloat(document.getElementById('inv-item-total').value);
  if (qty > 0 && Number.isFinite(total) && total >= 0) {
    document.getElementById('inv-item-price').value = Math.round((total / qty) * 10000) / 10000;
  }
}

function toggleQuickAddStock(forceState) {
  const box = document.getElementById('quick-add-stock-box');
  const nextShow = typeof forceState === 'boolean' ? forceState : box.classList.contains('hidden');
  box.classList.toggle('hidden', !nextShow);
}

function submitQuickAddStock() {
  const name = (document.getElementById('quick-stock-name').value || '').trim();
  const quantity = parseInt(document.getElementById('quick-stock-qty').value, 10) || 0;
  const purchasePrice = parseFloat(document.getElementById('quick-stock-buy').value) || 0;
  const sellingPrice = parseFloat(document.getElementById('quick-stock-sell').value) || 0;
  if (!name) {
    showToast('أدخل اسم الصنف أولاً', 'error');
    return;
  }
  if (!window.Android) return;
  const reqId = nextReqId((created) => {
    toggleQuickAddStock(false);
    document.getElementById('quick-stock-name').value = '';
    setTimeout(() => {
      if (created && created.id) {
        const sel = document.getElementById('inv-item-select');
        sel.value = String(created.id);
        onItemSelect();
      }
    }, 150);
  });
  window.Android.saveInventoryItem(JSON.stringify({
    id: 0,
    name,
    category: 'مواد غذائية',
    unit: 'حبة',
    quantity,
    purchasePrice,
    sellingPrice
  }), reqId);
}

function addCartItem() {
  const select = document.getElementById('inv-item-select');
  const itemId = parseInt(select.value, 10);
  if (!itemId) {
    showToast('اختر صنفاً من المخزن أو أضفه أولاً', 'error');
    return;
  }
  const opt = select.options[select.selectedIndex];
  const itemName = opt.dataset.name || opt.text.split('(')[0].trim();
  const qty = parseInt(document.getElementById('inv-item-qty').value, 10);
  const unitPrice = parseFloat(document.getElementById('inv-item-price').value);
  if (!Number.isFinite(qty) || qty <= 0) {
    showToast('أدخل كمية صحيحة أكبر من صفر', 'error');
    return;
  }
  if (!Number.isFinite(unitPrice) || unitPrice < 0) {
    showToast('أدخل سعر وحدة صحيحاً', 'error');
    return;
  }
  const totalPrice = Math.round(qty * unitPrice * 100) / 100;

  const existingIdx = cartItems.findIndex(x => x.itemId === itemId && Math.abs(x.unitPrice - unitPrice) < 0.0001);
  if (existingIdx >= 0) {
    cartItems[existingIdx].quantity += qty;
    cartItems[existingIdx].totalPrice = Math.round(cartItems[existingIdx].quantity * unitPrice * 100) / 100;
  } else {
    cartItems.push({ itemId, itemName, quantity: qty, unitPrice, totalPrice });
  }

  invoiceDirty = true;
  document.getElementById('inv-item-qty').value = '1';
  document.getElementById('inv-item-total').value = '';
  if (document.getElementById('inv-type').value === 'CASH') {
    const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
    document.getElementById('inv-paid').value = Math.round(subtotal * 100) / 100;
  }
  renderCart();
}

function updateCartQty(idx, newQtyVal) {
  const qty = parseInt(newQtyVal, 10);
  if (!Number.isFinite(qty) || qty <= 0) return;
  cartItems[idx].quantity = qty;
  cartItems[idx].totalPrice = Math.round(qty * cartItems[idx].unitPrice * 100) / 100;
  invoiceDirty = true;
  if (document.getElementById('inv-type').value === 'CASH') {
    const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
    document.getElementById('inv-paid').value = Math.round(subtotal * 100) / 100;
  }
  renderCart();
}

function removeCartItem(index) {
  cartItems.splice(index, 1);
  invoiceDirty = true;
  renderCart();
}

function clearCart() {
  cartItems = [];
  invoiceDirty = true;
  document.getElementById('inv-paid').value = '0';
  renderCart();
}

function renderCart() {
  const tbody = document.getElementById('cart-items-tbody');
  document.getElementById('inv-items-count-badge').textContent = `${cartItems.length} أصناف`;
  if (cartItems.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--text-secondary);">أضف أصنافاً من الشريط بالأعلى</td></tr>';
  } else {
    tbody.innerHTML = cartItems.map((item, idx) => `
      <tr>
        <td><strong>${escapeHtml(item.itemName)}</strong></td>
        <td><input type="number" class="qty-inline-input" min="1" value="${item.quantity}" onchange="updateCartQty(${idx}, this.value)"></td>
        <td>${fmtMoney(item.unitPrice)}</td>
        <td><strong class="text-success">${fmtMoney(item.totalPrice)}</strong></td>
        <td><button type="button" class="tbl-action-btn del" onclick="removeCartItem(${idx})">حذف</button></td>
      </tr>
    `).join('');
  }
  updateInvoiceTotals();
}

function setPaymentMode(mode) {
  if (document.getElementById('inv-type').value !== 'PURCHASE') {
    document.getElementById('inv-type').value = mode;
  }
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  document.getElementById('inv-paid').value = mode === 'CASH' ? Math.round(subtotal * 100) / 100 : 0;
  invoiceDirty = true;
  updatePaymentModeButtons();
  updateInvoiceTotals();
}

function updatePaymentModeButtons() {
  const type = document.getElementById('inv-type').value;
  document.getElementById('btn-mode-cash').classList.toggle('active-cash', type === 'CASH');
  document.getElementById('btn-mode-credit').classList.toggle('active-credit', type === 'CREDIT');
}

function payFullInvoice() {
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  document.getElementById('inv-paid').value = Math.round(subtotal * 100) / 100;
  invoiceDirty = true;
  updateInvoiceTotals();
}

function updateInvoiceTotals() {
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  const paid = parseFloat(document.getElementById('inv-paid').value) || 0;
  const remaining = Math.max(0, subtotal - paid);
  document.getElementById('inv-total-display').textContent = `الإجمالي: ${fmtMoney(subtotal)} ${appCurrency}`;
  document.getElementById('inv-remaining-display').textContent = `المتبقي من الفاتورة: ${fmtMoney(remaining)} ${appCurrency}`;

  const pill = document.getElementById('inv-status-pill');
  if (subtotal <= 0) {
    pill.textContent = 'مسودة';
    pill.className = 'badge badge-blue';
  } else if (remaining <= 0.001) {
    pill.textContent = 'خالص ✓';
    pill.className = 'badge badge-green';
  } else if (paid > 0) {
    pill.textContent = 'مدفوع جزئياً';
    pill.className = 'badge badge-amber';
  } else {
    pill.textContent = 'آجل';
    pill.className = 'badge badge-red';
  }
  computeExchangeChange();
}

function toggleExchangeCalc(forceState) {
  const box = document.getElementById('exchange-calc-box');
  const next = typeof forceState === 'boolean' ? forceState : box.classList.contains('hidden');
  box.classList.toggle('hidden', !next);
  if (next) computeExchangeChange();
}

function computeExchangeChange() {
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  const tendered = parseFloat(document.getElementById('calc-tendered').value) || 0;
  const rate = parseFloat(document.getElementById('calc-rate').value) || 0;
  const outEl = document.getElementById('calc-result-text');
  if (!outEl) return;

  const parts = [];
  if (tendered > 0) {
    const diff = tendered - subtotal;
    parts.push(diff >= 0
      ? `الباقي للعميل: ${fmtMoney(diff)} ${appCurrency}`
      : `ينقص على العميل: ${fmtMoney(-diff)} ${appCurrency}`);
  }
  if (rate > 0 && subtotal > 0) {
    parts.push(`يعادل: ${fmtMoney(subtotal / rate)} وحدة صرف`);
  }
  outEl.textContent = parts.length ? parts.join('   |   ') : 'أدخل المبلغ المستلم لحساب الباقي أو التحويل';
}

function saveInvoiceAction(followUpAction) {
  if (!window.Android) return;

  if (editingInvoiceId > 0 && !invoiceDirty && followUpAction !== 'NONE') {
    runInvoiceFollowUp(editingInvoiceId, followUpAction);
    return;
  }

  const number = (document.getElementById('inv-number').value || '').trim();
  const type = document.getElementById('inv-type').value;
  const partyId = parseInt(document.getElementById('inv-party').value, 10) || 0;
  const partySelect = document.getElementById('inv-party');
  const partyName = partySelect.options[partySelect.selectedIndex]
    ? partySelect.options[partySelect.selectedIndex].text.split('(')[0].trim()
    : 'عميل نقدي';
  const paid = parseFloat(document.getElementById('inv-paid').value) || 0;

  if (!number) {
    showToast('أدخل رقم الفاتورة', 'error');
    return;
  }
  if (cartItems.length === 0) {
    showToast('أضف صنفاً واحداً على الأقل للفاتورة', 'error');
    return;
  }
  if (type === 'PURCHASE' && partyId <= 0) {
    showToast('اختر المورد لفاتورة الشراء', 'error');
    return;
  }

  const payload = {
    id: editingInvoiceId,
    invoiceNumber: number,
    type,
    partyId,
    partyName,
    items: cartItems,
    paidAmount: paid
  };

  const reqId = nextReqId((data) => {
    const savedId = (data && data.invoiceId) ? data.invoiceId : editingInvoiceId;
    editingInvoiceId = savedId;
    invoiceDirty = false;
    document.getElementById('invoice-screen-title').textContent = 'تعديل الفاتورة';
    document.getElementById('btn-save-invoice').textContent = 'حفظ التعديل';
    document.getElementById('invoice-saved-badge').classList.remove('hidden');
    if (followUpAction && followUpAction !== 'NONE' && savedId > 0) {
      runInvoiceFollowUp(savedId, followUpAction);
    }
  });
  window.Android.saveInvoice(JSON.stringify(payload), reqId);
}

function runInvoiceFollowUp(invoiceId, action) {
  if (!window.Android || !invoiceId) return;
  if (action === 'THERMAL_PREVIEW') {
    activePreviewInvoiceId = invoiceId;
    window.Android.previewThermalInvoice(invoiceId, nextReqId());
  } else if (action === 'PDF') {
    window.Android.generateAndOpenPdf(invoiceId, nextReqId());
  } else if (action === 'WA_IMAGE') {
    window.Android.shareInvoiceImage(invoiceId, nextReqId());
  } else if (action === 'WA_PDF') {
    window.Android.shareInvoicePdf(invoiceId, nextReqId());
  }
}

window.onThermalPreviewData = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    activePreviewInvoiceId = data.invoiceId;
    document.getElementById('thermal-preview-title').textContent = `معاينة فاتورة #${data.invoiceNumber}`;
    document.getElementById('thermal-width-badge').textContent = `${data.widthMm} مم`;
    document.getElementById('thermal-printer-status-bar').textContent = data.connected
      ? `متصل بالطابعة: ${data.printerName || 'طابعة بلوتوث'}`
      : 'تنبيه: الطابعة الحرارية غير متصلة حالياً — يمكنك الاتصال بها من الإعدادات';
    document.getElementById('thermal-preview-img').src = 'data:image/png;base64,' + data.imageBase64;
    switchView('thermal-preview');
  } catch (e) {
    console.error(e);
  }
};

function printActivePreviewInvoice() {
  if (window.Android && activePreviewInvoiceId) {
    window.Android.printInvoice(activePreviewInvoiceId, nextReqId());
  }
}

function shareActivePreviewImage() {
  if (window.Android && activePreviewInvoiceId) {
    window.Android.shareInvoiceImage(activePreviewInvoiceId, nextReqId());
  }
}

function openActivePreviewPdf() {
  if (window.Android && activePreviewInvoiceId) {
    window.Android.generateAndOpenPdf(activePreviewInvoiceId, nextReqId());
  }
}

// ---------------- Reports ----------------

function setReportRange(range, btn) {
  reportRange = range;
  document.querySelectorAll('#report-range-tabs .pill').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  loadReports();
}

function loadReports() {
  if (!window.Android) return;
  const now = Date.now();
  let start = 0;
  if (reportRange === 'TODAY') {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    start = d.getTime();
  } else if (reportRange === 'WEEK') {
    start = now - 7 * 24 * 60 * 60 * 1000;
  } else if (reportRange === 'MONTH') {
    const d = new Date();
    d.setDate(1);
    d.setHours(0, 0, 0, 0);
    start = d.getTime();
  }
  window.Android.getReports(start, now);
}

window.onReportsData = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const container = document.getElementById('reports-list');
    const topItemsHtml = (data.topItems || []).length === 0
      ? '<div class="muted-text">لا توجد مبيعات في هذه الفترة</div>'
      : `<div class="table-wrap"><table class="data-table ruled-table"><thead><tr><th>الصنف</th><th>الكمية</th><th>الإيراد</th></tr></thead><tbody>` +
        data.topItems.map(item => `<tr><td>${escapeHtml(item.name)}</td><td>${item.quantity}</td><td><strong>${fmtMoney(item.revenue)}</strong></td></tr>`).join('') +
        `</tbody></table></div>`;

    const topDebtorsHtml = (data.topDebtors || []).length === 0
      ? '<div class="muted-text">لا توجد ديون مسجلة على العملاء</div>'
      : data.topDebtors.map(c => `
          <div class="list-item" onclick="openCustomerDetail(${c.id})">
            <strong>${escapeHtml(c.name)}</strong>
            <span class="text-danger" style="font-weight:800;">${fmtMoney(c.balance)} ${escapeHtml(appCurrency)}</span>
          </div>
        `).join('');

    container.innerHTML = `
      <div class="metrics-grid">
        <div class="metric-card"><div class="title">مبيعات الفترة (${data.invoiceCount || 0} فاتورة)</div><div class="value text-success">${fmtMoney(data.sales)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">صافي الربح (بعد COGS)</div><div class="value text-primary">${fmtMoney(data.grossProfit)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">تكلفة البضاعة المباعة</div><div class="value text-dark">${fmtMoney(data.cogs)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">إجمالي المشتريات</div><div class="value text-dark">${fmtMoney(data.purchases)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">ديون العملاء</div><div class="value text-danger">${fmtMoney(data.debts)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">مستحقات الموردين</div><div class="value text-primary">${fmtMoney(data.payables)} ${escapeHtml(appCurrency)}</div></div>
      </div>

      <div class="card">
        <h3>ملخص السيولة والمخزون</h3>
        <div class="info-pill">تحصيل نقدي من المبيعات: <strong>${fmtMoney(data.cashSales)} ${escapeHtml(appCurrency)}</strong></div>
        <div class="info-pill">مبيعات آجلة غير محصلة: <strong class="text-danger">${fmtMoney(data.creditSales)} ${escapeHtml(appCurrency)}</strong></div>
        <div class="info-pill">قيمة المخزون بالتكلفة: <strong>${fmtMoney(data.inventoryVal)} ${escapeHtml(appCurrency)}</strong></div>
        <div class="info-pill">قيمة المخزون بسعر البيع: <strong class="text-success">${fmtMoney(data.inventoryRetailVal)} ${escapeHtml(appCurrency)}</strong></div>
      </div>

      <div class="card">
        <h3>الأصناف الأكثر مبيعاً</h3>
        <div style="margin-top:8px;">${topItemsHtml}</div>
      </div>

      <div class="card">
        <h3>أعلى العملاء مديونية</h3>
        <div style="margin-top:8px;">${topDebtorsHtml}</div>
      </div>
    `;
  } catch (e) {
    console.error(e);
  }
};

// ---------------- Settings & Bluetooth ----------------

window.onSettingsData = function (jsonStr) {
  try {
    settingsData = JSON.parse(jsonStr);
    appCurrency = settingsData.currency || appCurrency;
    storeName = settingsData.storeName || storeName;
    document.getElementById('app-title').textContent = storeName;
    document.getElementById('drawer-store-name').textContent = storeName;

    document.getElementById('set-store-name').value = settingsData.storeName || '';
    document.getElementById('set-store-phone').value = settingsData.storePhone || '';
    document.getElementById('set-store-address').value = settingsData.storeAddress || '';
    document.getElementById('set-currency').value = settingsData.currency || 'ر.ي';

    const w58 = document.getElementById('width-btn-58');
    const w80 = document.getElementById('width-btn-80');
    w58.className = settingsData.printerWidthMm === 58 ? 'btn btn-primary' : 'btn btn-outline';
    w80.className = settingsData.printerWidthMm === 80 ? 'btn btn-primary' : 'btn btn-outline';

    const statusEl = document.getElementById('printer-status-text');
    statusEl.textContent = settingsData.printerConnected
      ? `متصل بالطابعة: ${settingsData.connectedPrinterName || settingsData.connectedPrinterAddress}`
      : 'حالة الطابعة: غير متصل';
    document.getElementById('btn-disconnect-printer').classList.toggle('hidden', !settingsData.printerConnected);
  } catch (e) {
    console.error(e);
  }
};

function saveStoreSettings() {
  if (!window.Android) return;
  const payload = {
    storeName: document.getElementById('set-store-name').value,
    storePhone: document.getElementById('set-store-phone').value,
    storeAddress: document.getElementById('set-store-address').value,
    currency: document.getElementById('set-currency').value
  };
  window.Android.saveStoreSettings(JSON.stringify(payload), nextReqId());
}

function setPrinterWidth(widthMm) {
  if (!window.Android) return;
  window.Android.setPrinterWidth(widthMm, nextReqId());
}

function refreshBluetoothDevices() {
  if (window.Android) {
    window.Android.getBluetoothDevices();
  }
}

window.onBluetoothDevices = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const container = document.getElementById('bluetooth-devices-list');
    if (!data.available) {
      container.innerHTML = '<div class="muted-text">هذا الجهاز لا يدعم البلوتوث</div>';
      return;
    }
    if (!data.enabled) {
      container.innerHTML = '<div class="muted-text">البلوتوث متوقف — قم بتشغيل البلوتوث واقرن الطابعة من إعدادات الجهاز ثم اضغط تحديث</div>';
      return;
    }
    const devices = data.devices || [];
    if (devices.length === 0) {
      container.innerHTML = '<div class="muted-text">لا توجد طابعات مقترنة حالياً</div>';
      return;
    }
    container.innerHTML = devices.map(d => {
      const isActive = data.connected && data.connectedAddress === d.address;
      const cls = isActive ? 'btn btn-success' : 'btn btn-outline';
      const suffix = isActive ? ' (متصل)' : '';
      return `<button type="button" class="${cls}" style="margin-top:6px;" onclick="connectPrinter('${escapeHtml(d.address)}')">${escapeHtml(d.name)} (${escapeHtml(d.address)})${suffix}</button>`;
    }).join('');
  } catch (e) {
    console.error(e);
  }
};

function connectPrinter(address) {
  if (window.Android) {
    window.Android.connectPrinter(address, nextReqId());
  }
}

function disconnectPrinter() {
  if (window.Android) {
    window.Android.disconnectPrinter(nextReqId());
  }
}

function printTestReceipt() {
  if (window.Android) {
    window.Android.printTestReceipt(nextReqId());
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

function toggleResetConfirm(show) {
  document.getElementById('reset-db-confirm').classList.toggle('hidden', !show);
}

function confirmResetDatabase() {
  if (!window.Android) return;
  const reqId = nextReqId(() => toggleResetConfirm(false));
  window.Android.resetAppDatabase(reqId);
}

window.onload = function () {
  const today = new Date();
  const label = document.getElementById('dash-date-label');
  if (label) {
    label.textContent = `${today.getFullYear()}/${String(today.getMonth() + 1).padStart(2, '0')}/${String(today.getDate()).padStart(2, '0')}`;
  }
  switchView('dashboard');
};
