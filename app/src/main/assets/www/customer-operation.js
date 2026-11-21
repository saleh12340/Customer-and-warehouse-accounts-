'use strict';

/* Customer operation entry screen. All writes use the existing AndroidBridge and Room records. */
let opSelectedCustomerId = 0;
let opCustomersReady = false;
let opSaving = false;
let opExpandedRecord = '';
let opOpenMenu = '';
let opPendingDeleteTxId = 0;

function openCustomerOperationScreen() {
  closeQuickActions();
  opSelectedCustomerId = 0;
  opExpandedRecord = '';
  opOpenMenu = '';
  activeCustomerDetail = null;
  const name = document.getElementById('op-customer-name');
  const amount = document.getElementById('op-customer-amount');
  if (name) name.value = '';
  if (amount) amount.value = '';
  const selected = document.getElementById('op-customer-selected');
  if (selected) selected.textContent = 'اختر عميلاً لعرض حسابه وعملياته';
  const history = document.getElementById('op-customer-history');
  if (history) history.innerHTML = '<div class="empty-state">اختر عميلاً لعرض سجله</div>';
  const suggestions = document.getElementById('op-customer-suggestions');
  if (suggestions) suggestions.classList.add('hidden');
  switchView('customer-operation');
  opCustomersReady = false;
  if (window.Android && window.Android.getCustomers) window.Android.getCustomers();
  setTimeout(() => name?.focus(), 120);
}
window.quickCustomerOperation = openCustomerOperationScreen;

function filterCustomerOperationSuggestions() {
  const input = document.getElementById('op-customer-name');
  const panel = document.getElementById('op-customer-suggestions');
  if (!input || !panel) return;
  const query = normalizeSuggestionText(input.value);
  if (!query) {
    panel.classList.add('hidden');
    panel.replaceChildren();
    return;
  }
  const matches = (customersData || []).filter(c =>
    normalizeSuggestionText(c.name).includes(query)
  ).slice(0, 8);
  panel.replaceChildren(...matches.map(customer => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'customer-op-suggestion';
    button.textContent = customer.name;
    button.addEventListener('click', () => selectCustomerForOperation(customer.id, customer.name));
    return button;
  }));
  panel.classList.toggle('hidden', matches.length === 0);
}
function selectCustomerForOperation(id, name) {
  opSelectedCustomerId = Number(id) || 0;
  const input = document.getElementById('op-customer-name');
  const panel = document.getElementById('op-customer-suggestions');
  if (input) input.value = name || '';
  if (panel) panel.classList.add('hidden');
  activeCustomerId = opSelectedCustomerId;
  activeCustomerDetail = null;
  const history = document.getElementById('op-customer-history');
  if (history) history.innerHTML = '<div class="empty-state">جارٍ تحميل حساب العميل...</div>';
  if (window.Android && opSelectedCustomerId) window.Android.getCustomerDetail(opSelectedCustomerId);
}
window.filterCustomerOperationSuggestions = filterCustomerOperationSuggestions;

const previousCustomersDataHandler = window.onCustomersData;
window.onCustomersData = function(jsonStr) {
  if (typeof previousCustomersDataHandler === 'function') previousCustomersDataHandler(jsonStr);
  opCustomersReady = true;
  if (currentView === 'customer-operation') filterCustomerOperationSuggestions();
};

const previousCustomerDetailHandler = window.onCustomerDetailData;
window.onCustomerDetailData = function(jsonStr) {
  if (typeof previousCustomerDetailHandler === 'function') previousCustomerDetailHandler(jsonStr);
  if (opPendingDeleteTxId && activeCustomerDetail) {
    const id = opPendingDeleteTxId;
    opPendingDeleteTxId = 0;
    askDeleteCustomerTx(id);
  }
  if (currentView !== 'customer-operation' || !activeCustomerDetail) return;
  if (Number(activeCustomerDetail.customer?.id) !== Number(opSelectedCustomerId)) return;
  renderCustomerOperationHistory();
};

function saveCustomerOperation(isDebit) {
  if (opSaving) return;
  const name = (document.getElementById('op-customer-name')?.value || '').trim();
  const rawAmount = document.getElementById('op-customer-amount')?.value || '';
  if (!name) { showToast('أدخل اسم العميل أولاً', 'error'); return; }
  if (!/^[0-9]+$/.test(rawAmount) || Number(rawAmount) <= 0) {
    showToast('أدخل المبلغ بالأرقام الإنجليزية 0–9 فقط', 'error');
    return;
  }
  if (!window.Android) { showToast('خدمة الحسابات غير متاحة', 'error'); return; }
  if (!opCustomersReady) { showToast('انتظر تحميل قائمة العملاء ثم أعد المحاولة', 'info'); return; }

  const normalizedName = normalizeSuggestionText(name);
  const existing = (customersData || []).find(c => normalizeSuggestionText(c.name) === normalizedName);
  opSaving = true;
  const amount = Number(rawAmount);
  if (opSelectedCustomerId && existing && Number(existing.id) === Number(opSelectedCustomerId)) {
    addCustomerOperationRecord(opSelectedCustomerId, amount, isDebit);
    return;
  }
  if (existing) {
    selectCustomerForOperation(existing.id, existing.name);
    addCustomerOperationRecord(existing.id, amount, isDebit);
    return;
  }

  // A new customer is created only after the user confirms a valid transaction.
  const payload = { id: 0, name, phone: '', address: '', balance: 0 };
  const reqId = nextReqId(data => {
    const id = Number(data?.id || 0);
    if (!id) { opSaving = false; showToast('تم حفظ العميل لكن تعذر استلام رقمه', 'error'); return; }
    opSelectedCustomerId = id;
    const input = document.getElementById('op-customer-name');
    if (input) input.value = name;
    activeCustomerId = id;
    addCustomerOperationRecord(id, amount, isDebit);
  }, () => { opSaving = false; });
  window.Android.saveCustomer(JSON.stringify(payload), reqId);
}

function addCustomerOperationRecord(customerId, amount, isDebit) {
  const done = () => {
    opSaving = false;
    const amountInput = document.getElementById('op-customer-amount');
    if (amountInput) amountInput.value = '';
    activeCustomerId = Number(customerId);
    if (window.Android.getCustomerDetail) window.Android.getCustomerDetail(Number(customerId));
    if (window.Android.getCustomers) window.Android.getCustomers();
    showToast('تم تسجيل العملية وتحديث حساب العميل', 'success');
  };
  const failed = () => { opSaving = false; };
  const reqId = nextReqId(done, failed);
  const dateMillis = Date.now();
  if (window.Android.addCustomerTransactionWithDate) {
    window.Android.addCustomerTransactionWithDate(Number(customerId), amount, Boolean(isDebit), 'عملية حساب', dateMillis, reqId);
  } else if (window.Android.addCustomerTransaction) {
    window.Android.addCustomerTransaction(Number(customerId), amount, Boolean(isDebit), 'عملية حساب', reqId);
  } else {
    opSaving = false;
    showToast('وظيفة تسجيل العملية غير متاحة في هذا الإصدار', 'error');
  }
}
window.saveCustomerOperation = saveCustomerOperation;

function openSelectedCustomerLedger() {
  if (!opSelectedCustomerId) { showToast('اختر عميلاً أولاً', 'error'); return; }
  openCustomerDetail(opSelectedCustomerId);
}
function exportCustomerOperationPdf() {
  if (!opSelectedCustomerId) { showToast('اختر عميلاً أولاً', 'error'); return; }
  exportStatementPdf('CUSTOMER', opSelectedCustomerId);
}
window.openSelectedCustomerLedger = openSelectedCustomerLedger;
window.exportCustomerOperationPdf = exportCustomerOperationPdf;

function opDateNumber(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric > 0 ? numeric : (Date.parse(value) || 0);
}
function renderCustomerOperationHistory() {
  const detail = activeCustomerDetail;
  const box = document.getElementById('op-customer-history');
  if (!detail || !box) return;
  const customer = detail.customer || {};
  const balance = Number(customer.balance || 0);
  const selected = document.getElementById('op-customer-selected');
  const balanceLabel = document.getElementById('op-customer-balance');
  const balanceText = balance > 0.01 ? 'عليه ' + fmtMoney(balance) : balance < -0.01 ? 'له ' + fmtMoney(-balance) : 'مسدد';
  if (selected) selected.textContent = customer.name + ' — ' + balanceText + ' ' + appCurrency;
  if (balanceLabel) {
    balanceLabel.textContent = balanceText;
    balanceLabel.className = 'muted-text ' + (balance > 0.01 ? 'text-danger' : balance < -0.01 ? 'text-primary' : 'text-dark');
  }

  const transactions = (detail.transactions || []).filter(t => !t.invoiceId);
  const invoices = detail.invoices || [];
  const linked = new Map((detail.transactions || []).filter(t => t.invoiceId).map(t => [Number(t.invoiceId), t]));
  const records = [
    ...transactions.map(t => ({ kind: 'TX', id: Number(t.id), date: t.date, amount: Number(t.amount || 0), debit: Boolean(t.isDebit), balance: t.runningBalance, detail: t.description || (t.isDebit ? 'عليه' : 'له'), source: t })),
    ...invoices.map(inv => {
      const tx = linked.get(Number(inv.id));
      return { kind: 'INV', id: Number(inv.id), date: inv.date, amount: Number(inv.totalAmount || 0), debit: true, balance: tx?.runningBalance, detail: 'فاتورة #' + (inv.invoiceNumber || inv.id), source: inv };
    })
  ].sort((a,b) => opDateNumber(b.date)-opDateNumber(a.date) || b.id-a.id);

  if (!records.length) {
    box.innerHTML = '<div class="empty-state">لا توجد عمليات أو فواتير لهذا العميل</div>';
    return;
  }
  box.innerHTML = records.map(record => {
    const key = record.kind + '-' + record.id;
    const expanded = opExpandedRecord === key;
    const menuOpen = opOpenMenu === key;
    const amountLabel = record.kind === 'TX' ? (record.debit ? 'عليه ' : 'له ') + fmtMoney(record.amount) : fmtMoney(record.amount);
    const amountClass = record.kind === 'TX' ? (record.debit ? 'debit' : 'credit') : 'debit';
    const balanceLabel = record.balance == null ? '—' : fmtMoney(record.balance);
    const details = record.kind === 'TX'
      ? escapeHtml(record.source.description || 'عملية حساب') + ' — ' + escapeHtml(fmtShortDate(record.date))
      : 'فاتورة ' + escapeHtml(invoiceTypeAr(record.source.type)) + ' • الإجمالي ' + fmtMoney(record.source.totalAmount) + ' • المدفوع ' + fmtMoney(record.source.paidAmount || 0) + ' • المتبقي ' + fmtMoney(record.source.remainingAmount || 0);
    const actionButtons = record.kind === 'TX'
      ? '<button type="button" onclick="customerOperationAction(\'TX\','+record.id+',\'edit\')">تعديل</button><button type="button" onclick="customerOperationAction(\'TX\','+record.id+',\'delete\')">حذف</button><button type="button" onclick="customerOperationAction(\'TX\','+record.id+',\'print\')">طباعة</button><button type="button" onclick="customerOperationAction(\'TX\','+record.id+',\'share\')">واتساب</button>'
      : '<button type="button" onclick="customerOperationAction(\'INV\','+record.id+',\'edit\')">فتح وتعديل</button><button type="button" onclick="customerOperationAction(\'INV\','+record.id+',\'delete\')">حذف</button><button type="button" onclick="customerOperationAction(\'INV\','+record.id+',\'print\')">طباعة</button><button type="button" onclick="customerOperationAction(\'INV\','+record.id+',\'share\')">واتساب</button>';
    return '<div class="customer-op-row" onclick="toggleCustomerOpExpanded(\''+key+'\')"><span class="op-date">'+escapeHtml(fmtShortDate(record.date))+'</span><span class="op-amount '+amountClass+'">'+amountLabel+'</span><span class="op-detail">'+escapeHtml(record.detail)+'</span><span class="op-balance">'+balanceLabel+'</span><button type="button" class="op-menu" aria-label="إجراءات" onclick="event.stopPropagation();toggleCustomerOpMenu(\''+key+'\')">⋮</button></div>'
      + (expanded ? '<div class="customer-op-expanded">'+details+(record.kind === 'INV' ? ' • رقم الفاتورة: '+escapeHtml(record.source.invoiceNumber || record.id) : '')+'</div>' : '')
      + (menuOpen ? '<div class="customer-op-menu">'+actionButtons+'</div>' : '');
  }).join('');
}
function toggleCustomerOpExpanded(key) {
  opExpandedRecord = opExpandedRecord === key ? '' : key;
  opOpenMenu = '';
  renderCustomerOperationHistory();
}
function toggleCustomerOpMenu(key) {
  opOpenMenu = opOpenMenu === key ? '' : key;
  renderCustomerOperationHistory();
}
function customerOperationAction(kind, id, action) {
  opOpenMenu = '';
  if (kind === 'TX') {
    if (action === 'edit') {
      pendingActivityTxToEdit = Number(id);
      pendingActivityPartyType = 'CUSTOMER';
      openCustomerDetail(opSelectedCustomerId);
    } else if (action === 'delete') {
      opPendingDeleteTxId = Number(id);
      openCustomerDetail(opSelectedCustomerId);
    } else if (action === 'print') previewTransactionThermal(Number(id));
    else if (action === 'share' && window.Android?.shareTransactionWhatsApp) window.Android.shareTransactionWhatsApp(Number(id), nextReqId());
  } else {
    if (action === 'edit') openEditInvoice(Number(id));
    else if (action === 'delete') deleteInvoiceWithConfirmation(Number(id));
    else if (action === 'print') quickThermalPreview(Number(id));
    else if (action === 'share') quickShareImage(Number(id));
  }
}
window.toggleCustomerOpExpanded = toggleCustomerOpExpanded;
window.toggleCustomerOpMenu = toggleCustomerOpMenu;
window.customerOperationAction = customerOperationAction;

document.addEventListener('input', event => {
  if (event.target?.id === 'op-customer-name') filterCustomerOperationSuggestions();
  if (event.target?.id === 'op-customer-amount') {
    const input = event.target;
    const cleaned = input.value.replace(/[^0-9]/g, '');
    if (cleaned !== input.value) input.value = cleaned;
  }
}, true);
document.addEventListener('focusout', event => {
  if (event.target?.id === 'op-customer-name') {
    setTimeout(() => document.getElementById('op-customer-suggestions')?.classList.add('hidden'), 140);
  }
});
