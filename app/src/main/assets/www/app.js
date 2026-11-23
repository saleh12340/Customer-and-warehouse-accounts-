'use strict';

const NUMERIC_FIELD_SELECTOR = 'input[type="number"], input[inputmode="numeric"], input[inputmode="decimal"], input[type="tel"], input[type="date"], input[type="datetime-local"], input[type="time"], input[data-numeric-input]';

function toEnglishDigits(value) {
  return String(value ?? '')
    .replace(/[٠-٩۰-۹]/g, ch => {
      const code = ch.charCodeAt(0);
      return String(code >= 0x06F0 ? code - 0x06F0 : code - 0x0660);
    })
    .replace(/\u066B/g, '.')
    .replace(/\u066C/g, '');
}

function normalizeNumericField(el) {
  if (!el || !el.matches || !el.matches(NUMERIC_FIELD_SELECTOR)) return;
  // Dedicated English-only fields opt out of the app-wide Arabic-digit conversion.
  if (el.hasAttribute('data-english-only')) {
    el.setAttribute('lang', 'en-US');
    el.setAttribute('dir', 'ltr');
    el.style.direction = 'ltr';
    el.style.unicodeBidi = 'isolate';
    el.style.fontFamily = 'Arial, Roboto, sans-serif';
    el.style.fontVariantNumeric = 'lining-nums tabular-nums';
    return;
  }
  const wasNumber = el.type === 'number';
  const step = el.getAttribute('step') || '1';
  if (wasNumber) {
    // Numeric text controls avoid locale-dependent digit glyphs in Android WebView.
    el.type = 'text';
    el.setAttribute('data-numeric-input', 'true');
    el.setAttribute('inputmode', step === '1' ? 'numeric' : 'decimal');
    el.setAttribute('autocomplete', 'off');
  }
  el.setAttribute('lang', 'en-US');
  el.setAttribute('dir', 'ltr');
  el.style.direction = 'ltr';
  el.style.unicodeBidi = 'isolate';
  el.style.fontFamily = 'Arial, Roboto, sans-serif';
  el.style.fontVariantNumeric = 'lining-nums tabular-nums';
  const normalized = toEnglishDigits(el.value);
  if (normalized !== el.value) el.value = normalized;
}

function enforceEnglishNumericFields(root) {
  const scope = root || document;
  if (scope.matches && scope.matches(NUMERIC_FIELD_SELECTOR)) normalizeNumericField(scope);
  if (scope.querySelectorAll) scope.querySelectorAll(NUMERIC_FIELD_SELECTOR).forEach(normalizeNumericField);
}

function normalizeNumericEvent(event) {
  const el = event.target;
  if (!el || !el.matches || !el.matches(NUMERIC_FIELD_SELECTOR)) return;
  // The dedicated customer-operation amount rejects non-ASCII digits instead of converting them.
  if (el.hasAttribute('data-english-only')) return;
  normalizeNumericField(el);
  const normalized = toEnglishDigits(el.value);
  if (normalized !== el.value) {
    let start = null;
    try { start = el.selectionStart; } catch (_) {}
    el.value = normalized;
    if (typeof start === 'number') {
      try { el.setSelectionRange(start, start); } catch (_) {}
    }
  }
}

document.addEventListener('DOMContentLoaded', () => enforceEnglishNumericFields());
document.addEventListener('input', normalizeNumericEvent, true);
document.addEventListener('change', normalizeNumericEvent, true);
document.addEventListener('compositionend', normalizeNumericEvent, true);

// Apply the policy to every newly-rendered numeric field in every screen.
if (typeof MutationObserver !== 'undefined' && document.documentElement) {
  const numericObserver = new MutationObserver(records => {
    records.forEach(record => record.addedNodes.forEach(node => {
      if (node.nodeType === 1) enforceEnglishNumericFields(node);
    }));
  });
  numericObserver.observe(document.documentElement, { childList: true, subtree: true });
  enforceEnglishNumericFields();
}

let currentView = 'dashboard';
let activityLogData = [];
let pendingActivityTxToEdit = 0;
let pendingActivityPartyType = '';
let pendingQuickCustomerOperation = false;
let noteEditorId = 0;
let noteEditorDirty = false;
let noteAutosaveTimer = null;
let noteAutosaveRetryTimer = null;
let noteAutosaveRetryCount = 0;
let noteAutosaveInFlight = false;
let noteAutosavePending = false;
let noteManualSavePending = false;
let noteEditRevision = 0;
let noteEditorSession = 0;
let restoreLastNoteOnLoad = false;
let refreshNotesHistoryAfterSave = false;
let noteRightRows = [{ qty: '', detail: '' }];
let noteLeftRows = [{ qty: '', detail: '' }];
let savedNotes = [];
let viewHistory = [];
let appCurrency = 'ر.ي';
let storeName = 'بقالة العزي للمواد الغذائية';

let customersData = [];
let suppliersData = [];
let inventoryData = [];
let activeInventoryHistoryItemId = 0;
let pendingInventoryAdjustmentId = 0;
let invoicesData = [];
let settingsData = {
  storeName: 'بقالة العزي للمواد الغذائية',
  storePhone: '',
  storeAddress: '',
  currency: 'ر.ي',
  uiTextScale: 'NORMAL',
  compactLists: false,
  defaultInvoiceType: 'CASH',
  autoFillCashPaid: true,
  confirmBeforeDeleteInvoice: true,
  quickPartyCount: 5,
  lowStockThreshold: 10,
  defaultInventorySort: 'NAME',
  defaultInventoryUnit: 'حبة',
  defaultInventoryCategory: 'مواد غذائية',
  defaultCustomerSort: 'NAME',
  promptWhatsAppAfterTx: true,
  showZeroBalanceParties: true,
  defaultReportRange: 'ALL',
  showUncostedProfitWarning: true,
  printerWidthMm: 58,
  defaultPrinterAddress: '',
  defaultPrinterName: '',
  requireThermalPreview: true,
  autoOpenPdfAfterExport: true,
  preferWhatsAppDirect: true,
  autoSaveThermalImage: false,
  linkedFolderActive: false,
  folderDisplayPath: 'Download/بقاله العزي',
  hasSafetyBackup: false,
  printerConnected: false,
  connectedPrinterName: '',
  connectedPrinterAddress: '',
  bluetoothAvailable: false,
  bluetoothEnabled: false
};
let settingsLoadedOnce = false;
let isSavingInvoice = false;


// ---------------- Compact context suggestions (shared across all screens) ----------------
const SUGGESTION_LIST_IDS = {
  customers: 'suggest-customers', suppliers: 'suggest-suppliers', items: 'suggest-items',
  units: 'suggest-units', categories: 'suggest-categories',
  customerDescriptions: 'suggest-customer-descriptions', supplierDescriptions: 'suggest-supplier-descriptions'
};
const DEFAULT_UNIT_SUGGESTIONS = ['كيس', 'نص كيس', 'دزن', 'حبة'];
const CUSTOM_UNIT_STORAGE_KEY = 'alaziy_custom_inventory_units_v1';
const UNIT_SELECT_IDS = [
  'inventory-quick-unit', 'inventory-quick-carton-name', 'inventory-quick-pack-name',
  'inv-item-form-unit', 'inv-item-form-carton-name', 'inv-item-form-pack-name',
  'set-default-inventory-unit'
];
function readCustomInventoryUnits() {
  try {
    const stored = JSON.parse(localStorage.getItem(CUSTOM_UNIT_STORAGE_KEY) || '[]');
    return Array.isArray(stored) ? stored.filter(x => typeof x === 'string' && x.trim()) : [];
  } catch (_) { return []; }
}
function getAvailableInventoryUnits() {
  const values = [...DEFAULT_UNIT_SUGGESTIONS, ...readCustomInventoryUnits()];
  return uniqueSuggestionValues(values);
}
function refreshUnitSelectOptions(target) {
  const selects = target instanceof HTMLSelectElement
    ? [target]
    : (target?.querySelectorAll ? [...target.querySelectorAll('select[data-unit-select]')] : []);
  selects.forEach(select => {
    const current = String(select.value || '').trim();
    const units = getAvailableInventoryUnits();
    select.replaceChildren();
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = 'اختر الوحدة';
    select.appendChild(placeholder);
    units.forEach(unit => {
      const option = document.createElement('option');
      option.value = unit;
      option.textContent = unit;
      select.appendChild(option);
    });
    if (current && !units.some(unit => normalizeSuggestionText(unit) === normalizeSuggestionText(current))) {
      const legacy = document.createElement('option');
      legacy.value = current;
      legacy.textContent = current;
      select.appendChild(legacy);
    }
    select.value = current;
    select.dataset.previousValue = current;
  });
}
function initializeUnitSelectors() {
  refreshUnitSelectOptions(document);
  document.addEventListener('focusin', event => {
    if (event.target?.matches?.('select[data-unit-select]')) refreshUnitSelectOptions(event.target);
  });
}
function handleUnitSelect(select) {
  if (!select || !select.matches('select[data-unit-select]')) return;
  select.dataset.previousValue = select.value || '';
}

let editingManagedUnitName = '';
function renderManagedUnits() {
 const list=document.getElementById('unit-manager-list'); const count=document.getElementById('unit-manager-count'); if(!list)return;
 const defaults=DEFAULT_UNIT_SUGGESTIONS; const custom=readCustomInventoryUnits();
 const rows=[...defaults.map(name=>({name:name,fixed:true})),...custom.map(name=>({name:name,fixed:false}))];
 if(count)count.textContent=String(rows.length);
 list.innerHTML=rows.length?rows.map((row,index)=>{
  const safe=escapeHtml(row.name);
  const actions=row.fixed?'<span class="muted-text">أساسية</span>':'<div class="btn-row-compact"><button type="button" class="btn btn-outline-primary btn-sm" onclick="editManagedUnitAt('+index+')">تعديل</button><button type="button" class="btn btn-danger btn-sm" onclick="deleteManagedUnitAt('+index+')">حذف</button></div>';
  return '<div class="unit-manager-row"><div class="unit-manager-name"><strong>'+safe+'</strong><small>'+(row.fixed?'وحدة أساسية':'وحدة مضافة')+'</small></div>'+actions+'</div>';
 }).join(''):'<div class="empty-state">لا توجد وحدات مضافة</div>';
}
function saveManagedUnit(){
 const input=document.getElementById('unit-manager-name'); const name=String(input?.value||'').trim();
 if(!name){showToast('اكتب اسم الوحدة أولاً','warning');input?.focus();return;}
 if(DEFAULT_UNIT_SUGGESTIONS.some(x=>normalizeSuggestionText(x)===normalizeSuggestionText(name))){showToast('هذه الوحدة من الوحدات الأساسية','warning');return;}
 const custom=readCustomInventoryUnits();
 if(custom.some(x=>normalizeSuggestionText(x)===normalizeSuggestionText(name)&&normalizeSuggestionText(x)!==normalizeSuggestionText(editingManagedUnitName))){showToast('هذه الوحدة موجودة بالفعل','warning');return;}
 const next=uniqueSuggestionValues(editingManagedUnitName?custom.map(x=>normalizeSuggestionText(x)===normalizeSuggestionText(editingManagedUnitName)?name:x):[...custom,name]);
 try{localStorage.setItem(CUSTOM_UNIT_STORAGE_KEY,JSON.stringify(next));}catch(_){showToast('تعذر حفظ الوحدات على هذا الجهاز','error');return;}
 const wasEditing=Boolean(editingManagedUnitName); cancelManagedUnitEdit(); refreshUnitSelectOptions(document); renderManagedUnits();
 showToast(wasEditing?'تم تعديل الوحدة':'تمت إضافة الوحدة','success');
}
function editManagedUnitAt(index){const custom=readCustomInventoryUnits();const name=custom[index-DEFAULT_UNIT_SUGGESTIONS.length];if(name===undefined)return;editingManagedUnitName=name;const input=document.getElementById('unit-manager-name');input.value=name;document.getElementById('unit-editor-title').textContent='تعديل الوحدة';document.getElementById('unit-manager-save').textContent='حفظ التعديل';document.getElementById('unit-manager-cancel')?.classList.remove('hidden');input.focus();}
function cancelManagedUnitEdit(){editingManagedUnitName='';const input=document.getElementById('unit-manager-name');if(input)input.value='';const title=document.getElementById('unit-editor-title');if(title)title.textContent='إضافة وحدة جديدة';const save=document.getElementById('unit-manager-save');if(save)save.textContent='إضافة الوحدة';document.getElementById('unit-manager-cancel')?.classList.add('hidden');}
function deleteManagedUnitAt(index){const custom=readCustomInventoryUnits();const name=custom[index-DEFAULT_UNIT_SUGGESTIONS.length];if(name===undefined)return;const next=custom.filter((_,i)=>i!==index-DEFAULT_UNIT_SUGGESTIONS.length);try{localStorage.setItem(CUSTOM_UNIT_STORAGE_KEY,JSON.stringify(next));}catch(_){showToast('تعذر حذف الوحدة','error');return;}if(normalizeSuggestionText(editingManagedUnitName)===normalizeSuggestionText(name))cancelManagedUnitEdit();refreshUnitSelectOptions(document);renderManagedUnits();showToast('تم حذف الوحدة من قائمة الوحدات','success');}

const MAX_CONTEXT_SUGGESTIONS = 3;
const MIN_CONTEXT_QUERY_LENGTH = 2;
let activeSuggestionInput = null;
let activeSuggestionItems = [];
let activeSuggestionIndex = -1;
let suggestionHideFrame = 0;

function normalizeSuggestionText(value) {
  return String(value ?? '').trim().toLocaleLowerCase('ar')
    .replace(/[\u064B-\u065F\u0670]/g, '')
    .replace(/[أإآ]/g, 'ا').replace(/ى/g, 'ي');
}
function uniqueSuggestionValues(values) {
  const seen = new Set();
  return (values || []).map(v => String(v ?? '').trim()).filter(v => {
    const key = normalizeSuggestionText(v);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  }).sort((a, b) => a.localeCompare(b, 'ar'));
}
function suggestionValuesFor(kind) {
  if (kind === 'customers') return uniqueSuggestionValues(customersData.map(x => x.name));
  if (kind === 'suppliers') return uniqueSuggestionValues(suppliersData.map(x => x.name));
  if (kind === 'items') {
    const values = (inventoryData || []).map(x => x.name);
    (savedNotes || []).forEach(note => [...(note.rightRows || []), ...(note.leftRows || [])].forEach(row => values.push(row.detail)));
    [...(noteRightRows || []), ...(noteLeftRows || [])].forEach(row => values.push(row.detail));
    (cartItems || []).forEach(row => values.push(row.itemName || row.name));
    (batchEntryRows || []).forEach(row => values.push(row.itemName));
    return uniqueSuggestionValues(values);
  }
  if (kind === 'units') return getAvailableInventoryUnits();
  if (kind === 'categories') return uniqueSuggestionValues((inventoryData || []).map(x => x.category));
  if (kind === 'customerDescriptions') return uniqueSuggestionValues((activeCustomerDetail?.transactions || []).map(x => x.description));
  if (kind === 'supplierDescriptions') return uniqueSuggestionValues((activeSupplierDetail?.transactions || []).map(x => x.description));
  return [];
}
function suggestionKindForInput(input) {
  if (!input || (input.tagName !== 'INPUT' && input.tagName !== 'TEXTAREA')) return '';
  const id = input.id || '';
  if (id === 'cust-form-name' || input.matches('[data-suggest="customers"]')) return 'customers';
  if (id === 'quick-party-name') return (document.getElementById('inv-type')?.value || 'CASH') === 'PURCHASE' ? 'suppliers' : 'customers';
  if (id === 'supp-form-name' || input.matches('[data-suggest="suppliers"]')) return 'suppliers';
  if (/^(batch-name-\d+|note-detail|inv-item-form-name|inventory-quick-name|quick-stock-name)$/.test(id) ||
      input.classList?.contains('note-detail') || input.matches('[data-suggest="items"]')) return 'items';
  if (id === 'inventory-quick-category' || id === 'inv-item-form-category' || input.matches('[data-suggest="categories"]')) return 'categories';
  if (id === 'inventory-quick-unit' || id === 'inv-item-form-unit' || id.includes('pack-name') || id.includes('carton-name') ||
      input.matches('[data-suggest="units"]')) return 'units';
  if (id === 'tx-desc' || input.matches('[data-suggest="customerDescriptions"]')) return 'customerDescriptions';
  if (id === 'supp-tx-desc' || input.matches('[data-suggest="supplierDescriptions"]')) return 'supplierDescriptions';
  return '';
}
function ensureCompactSuggestionPanel() {
  let panel = document.getElementById('context-suggestion-panel');
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'context-suggestion-panel';
    panel.className = 'context-suggestion-panel hidden';
    panel.setAttribute('role', 'listbox');
    panel.setAttribute('aria-label', 'اقتراحات');
    document.body.appendChild(panel);
  }
  return panel;
}
function hideContextSuggestions() {
  const panel = document.getElementById('context-suggestion-panel');
  if (panel) { panel.classList.add('hidden'); panel.replaceChildren(); }
  if (activeSuggestionInput) activeSuggestionInput.removeAttribute('aria-activedescendant');
  activeSuggestionInput = null;
  activeSuggestionItems = [];
  activeSuggestionIndex = -1;
}
function positionContextSuggestions() {
  const panel = document.getElementById('context-suggestion-panel');
  if (!panel || panel.classList.contains('hidden') || !activeSuggestionInput?.isConnected) return;
  const rect = activeSuggestionInput.getBoundingClientRect();
  const gap = 3, maxHeight = Math.min(126, window.innerHeight * 0.24);
  const below = window.innerHeight - rect.bottom;
  const placeAbove = below < Math.min(maxHeight, 105) && rect.top > below;
  panel.style.left = Math.max(6, Math.min(rect.left, window.innerWidth - Math.max(rect.width, 150) - 6)) + 'px';
  panel.style.width = Math.min(Math.max(rect.width, 150), window.innerWidth - 12) + 'px';
  panel.style.maxHeight = maxHeight + 'px';
  panel.style.top = (placeAbove ? Math.max(6, rect.top - Math.min(panel.offsetHeight || maxHeight, maxHeight) - gap) : rect.bottom + gap) + 'px';
}
function chooseContextSuggestion(index) {
  if (!activeSuggestionInput || !activeSuggestionItems[index]) return;
  const input = activeSuggestionInput;
  input.value = activeSuggestionItems[index];
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
  hideContextSuggestions();
  input.focus();
}
function refreshSuggestionListForInput(input) {
  const kind = suggestionKindForInput(input);
  if (!kind) { hideContextSuggestions(); return; }
  const query = normalizeSuggestionText(input.value);
  // Invoice item names are selected from the item box by default.
  // Show autocomplete suggestions only after the user has typed at least two characters.
  const isInvoiceItemName = /^batch-name-\\d+$/.test(input.id || '');
  if (isInvoiceItemName && query.length < MIN_CONTEXT_QUERY_LENGTH) {
    hideContextSuggestions();
    return;
  }
  const allValues = suggestionValuesFor(kind);
  // Other supported fields retain focus suggestions; invoice items require a two-character query.
  const matches = allValues.map(value => ({
    value, normalized: normalizeSuggestionText(value)
  })).filter(item => !query ||
      (item.normalized !== query && (item.normalized.startsWith(query) || item.normalized.includes(query))))
    .sort((a, b) => {
      if (!query) return a.value.localeCompare(b.value, 'ar');
      const ap = a.normalized.startsWith(query) ? 0 : 1;
      const bp = b.normalized.startsWith(query) ? 0 : 1;
      return ap - bp || a.value.localeCompare(b.value, 'ar');
    }).slice(0, MAX_CONTEXT_SUGGESTIONS);
  if (!matches.length) { hideContextSuggestions(); return; }
  activeSuggestionInput = input;
  activeSuggestionItems = matches.map(item => item.value);
  activeSuggestionIndex = -1;
  const panel = ensureCompactSuggestionPanel();
  panel.replaceChildren(...activeSuggestionItems.map((value, index) => {
    const option = document.createElement('button');
    option.type = 'button';
    option.className = 'context-suggestion-option';
    option.id = 'context-suggestion-' + index;
    option.setAttribute('role', 'option');
    option.textContent = value;
    option.addEventListener('pointerdown', event => {
      event.preventDefault();
      chooseContextSuggestion(index);
    });
    option.addEventListener('click', event => event.preventDefault());
    return option;
  }));
  panel.classList.remove('hidden');
  positionContextSuggestions();
}
function refreshContextSuggestions(root) {
  const scope = root || document;
  if (scope.matches && scope.matches('input,textarea')) {
    const kind = suggestionKindForInput(scope);
    if (kind) { scope.setAttribute('autocomplete', 'off'); scope.setAttribute('spellcheck', 'false'); }
  }
  if (scope.querySelectorAll) scope.querySelectorAll('input,textarea').forEach(input => {
    if (suggestionKindForInput(input)) {
      input.setAttribute('autocomplete', 'off');
      input.setAttribute('spellcheck', 'false');
    }
  });
}
function initializeContextSuggestions() {
  refreshContextSuggestions();
  if (typeof MutationObserver !== 'undefined' && document.documentElement) {
    const observer = new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(node => {
      if (node.nodeType === 1) refreshContextSuggestions(node);
    })));
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
  document.addEventListener('input', event => {
    if (suggestionKindForInput(event.target)) refreshSuggestionListForInput(event.target);
    else hideContextSuggestions();
  }, true);
  document.addEventListener('focusin', event => {
    const input = event.target;
    if (suggestionKindForInput(input)) {
      refreshSuggestionListForInput(input);
    } else hideContextSuggestions();
  });
  document.addEventListener('focusout', event => {
    const leavingInput = event.target;
    if (leavingInput === activeSuggestionInput) {
      suggestionHideFrame = requestAnimationFrame(() => {
        // A different suggestion-enabled input may have received focus in the same frame.
        if (activeSuggestionInput === leavingInput &&
            !document.getElementById('context-suggestion-panel')?.contains(document.activeElement)) {
          hideContextSuggestions();
        }
      });
    }
  });
  document.addEventListener('keydown', event => {
    if (!activeSuggestionInput || !activeSuggestionItems.length) return;
    if (event.key === 'Escape') { hideContextSuggestions(); return; }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      activeSuggestionIndex = (activeSuggestionIndex + (event.key === 'ArrowDown' ? 1 : -1) + activeSuggestionItems.length) % activeSuggestionItems.length;
      document.querySelectorAll('.context-suggestion-option').forEach((el, i) => el.classList.toggle('is-active', i === activeSuggestionIndex));
      return;
    }
    if (event.key === 'Enter' && activeSuggestionIndex >= 0) {
      event.preventDefault();
      chooseContextSuggestion(activeSuggestionIndex);
    }
  }, true);
  document.addEventListener('pointerdown', event => {
    if (activeSuggestionInput && event.target !== activeSuggestionInput &&
        !document.getElementById('context-suggestion-panel')?.contains(event.target)) hideContextSuggestions();
  }, true);
  window.addEventListener('resize', positionContextSuggestions, { passive: true });
  window.visualViewport?.addEventListener('resize', positionContextSuggestions, { passive: true });
  window.visualViewport?.addEventListener('scroll', positionContextSuggestions, { passive: true });
  document.getElementById('main-scroll')?.addEventListener('scroll', positionContextSuggestions, { passive: true });
}
document.addEventListener('DOMContentLoaded', initializeContextSuggestions);
document.addEventListener('DOMContentLoaded', initializeUnitSelectors);

let customerFilter = 'ALL';
let inventoryFilter = 'ALL';
let inventoryCategoryFilter = 'ALL';
let inventorySort = 'NAME';
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
let verifiedPreviewSignature = '';
let activePreviewState = {
  kind: 'INVOICE',
  targetId: 0,
  extraStr: '',
  startMillis: 0,
  endMillis: 0
};
let thermalReturnView = 'create-invoice';
let batchEntryRows = [];
let lastInvoiceType = '';

let reqSeq = 1;
const pendingCallbacks = {};
let toastTimer = null;
let pendingInvoicePartyId = 0;

function nextReqId(onSuccess, onError) {
  const id = 'req_' + (reqSeq++);
  if (typeof onSuccess === 'function' || typeof onError === 'function') {
    pendingCallbacks[id] = { onSuccess, onError };
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
  const cbEntry = pendingCallbacks[reqId];
  if (cbEntry) {
    // Always release one-shot callbacks, including failed operations.
    delete pendingCallbacks[reqId];
    if (typeof cbEntry === 'function') {
      if (success) cbEntry(parsed);
    } else {
      if (success && typeof cbEntry.onSuccess === 'function') cbEntry.onSuccess(parsed);
      if (!success && typeof cbEntry.onError === 'function') cbEntry.onError(message, parsed);
    }
  }
};

window.onCustomerContactPicked = function (jsonStr) {
  try {
    const contact = JSON.parse(jsonStr || '{}');
    const name = String(contact.name || '').trim();
    const phone = String(contact.phone || '').trim();
    if (!name && !phone) {
      showToast('جهة الاتصال المحددة لا تحتوي على اسم أو رقم هاتف', 'error');
      return;
    }
    const nameInput = document.getElementById('cust-form-name');
    const phoneInput = document.getElementById('cust-form-phone');
    if (nameInput) nameInput.value = name;
    if (phoneInput) phoneInput.value = phone;
    showToast('تم استيراد بيانات جهة الاتصال؛ راجعها ثم احفظ العميل', 'success');
  } catch (e) {
    showToast('تعذر قراءة بيانات جهة الاتصال', 'error');
    console.error(e);
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

function fmtShortDate(millis) {
  if (!millis) return '';
  const d = new Date(millis);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${m}/${day} ${hh}:${mm}`;
}

function toDateTimeLocalValue(millis) {
  const d = millis ? new Date(millis) : new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${day}T${hh}:${mm}`;
}

function fromDateTimeLocalValue(val) {
  if (!val) return 0;
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec(String(val).trim());
  if (match) {
    const localDate = new Date(
      Number(match[1]),
      Number(match[2]) - 1,
      Number(match[3]),
      Number(match[4] || 0),
      Number(match[5] || 0),
      Number(match[6] || 0),
      0
    );
    const ms = localDate.getTime();
    return Number.isFinite(ms) && ms > 0 ? ms : 0;
  }
  const ms = Date.parse(val);
  return Number.isFinite(ms) && ms > 0 ? ms : 0;
}

function invoiceTypeAr(type) {
  switch ((type || '').toUpperCase()) {
    case 'CASH': return 'فاتورة بيع نقدي';
    case 'CREDIT': return 'فاتورة بيع آجل';
    case 'SALE': return 'فاتورة بيع';
    case 'PURCHASE': return 'فاتورة شراء';
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
  'activity-log': 'dashboard',
  'notes-history': 'notes',
  'suppliers': 'dashboard',
  'settings': 'dashboard',
  'units': 'settings',
  'about': 'dashboard'
};

function switchView(viewName, trackHistory = true) {
  if (currentView === 'notes' && viewName !== 'notes' && noteEditorDirty) {
    clearTimeout(noteAutosaveTimer);
    performNoteSave();
  }
  if (pendingQuickCustomerOperation && viewName !== 'customers' && viewName !== 'customer-detail') pendingQuickCustomerOperation = false;
  if (viewName === currentView) {
    loadViewData(viewName);
    return;
  }
  if (trackHistory && currentView) {
    viewHistory.push(currentView);
    if (viewHistory.length > 40) viewHistory.shift();
  }
  if (currentView === 'create-invoice') {
    toggleMultiStockPicker(false);
    toggleInvoiceCartFullscreen(false);
  }
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
  document.querySelectorAll('.bottom-nav button').forEach(b => b.classList.remove('active'));

  const viewEl = document.getElementById(`view-${viewName}`);
  if (viewEl) viewEl.classList.add('active');

  const navEl = document.getElementById(`nav-${viewName}`);
  if (navEl) navEl.classList.add('active');

  const backBtn = document.getElementById('header-back-btn');
  if (backBtn) {
    backBtn.classList.toggle('hidden', !(SUB_VIEW_PARENTS[viewName] || viewHistory.length > 0));
  }

  currentView = viewName;
  const scroll = document.getElementById('main-scroll');
  if (scroll) scroll.scrollTop = 0;

  loadViewData(viewName);
}

function navigateToPreviousView() {
  if (currentView === 'thermal-preview') {
    const dest = viewHistory.pop() || thermalReturnView || 'create-invoice';
    switchView(dest, false);
    return true;
  }
  while (viewHistory.length > 0) {
    const previous = viewHistory.pop();
    if (previous !== currentView) {
      switchView(previous, false);
      return true;
    }
  }
  const parent = SUB_VIEW_PARENTS[currentView];
  if (parent) {
    switchView(parent, false);
    return true;
  }
  if (currentView !== 'dashboard') {
    switchView('dashboard', false);
    return true;
  }
  return false;
}

function handleBackAction() {
  navigateToPreviousView();
}

document.addEventListener('keydown', event => {
  if (event.key !== 'Escape' && event.key !== 'BrowserBack') return;
  const stockPicker = document.getElementById('multi-stock-picker-box');
  if (stockPicker && !stockPicker.classList.contains('hidden')) {
    event.preventDefault();
    toggleMultiStockPicker(false);
    return;
  }
  const cartPanel = document.getElementById('invoice-cart-panel');
  if (cartPanel && cartPanel.classList.contains('is-fullscreen')) {
    event.preventDefault();
    toggleInvoiceCartFullscreen(false);
  }
});

window.handleAndroidBack = function () {
  const quickSheet = document.getElementById('quick-action-sheet');
  if (quickSheet && !quickSheet.classList.contains('hidden')) {
    closeQuickActions();
    return 'HANDLED';
  }
  const overflowMenu = document.getElementById('header-overflow-menu');
  if (overflowMenu && !overflowMenu.classList.contains('hidden')) {
    toggleHeaderMenu(false);
    return 'HANDLED';
  }
  const stockPicker = document.getElementById('multi-stock-picker-box');
  if (stockPicker && !stockPicker.classList.contains('hidden')) {
    toggleMultiStockPicker(false);
    return 'HANDLED';
  }
  const cartPanel = document.getElementById('invoice-cart-panel');
  if (cartPanel && cartPanel.classList.contains('is-fullscreen')) {
    toggleInvoiceCartFullscreen(false);
    return 'HANDLED';
  }
  const menu = document.getElementById('side-menu');
  if (menu && !menu.classList.contains('hidden')) {
    toggleMenu(false);
    return 'HANDLED';
  }
  return navigateToPreviousView() ? 'HANDLED' : 'EXIT';
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
  } else if (view === 'activity-log') {
    loadActivityLog();
  } else if (view === 'notes') {
    restoreLastNoteOnLoad = true;
    renderNoteEditor();
    window.Android.getNotes();
  } else if (view === 'notes-history') {
    const list = document.getElementById('notes-history-list');
    if (list) list.innerHTML = '<div class="empty-state">جارٍ تحميل سجل الملاحظات...</div>';
    if (!refreshNotesHistoryAfterSave) window.Android.getNotes();
  } else if (view === 'reports') {
    loadReports();
  } else if (view === 'settings') {
    window.Android.getSettings();
    window.Android.getBluetoothDevices();
  } else if (view === 'units') {
    renderManagedUnits();
  }
}


function toggleHeaderMenu(forceState) {
  const menu=document.getElementById('header-overflow-menu'); if(!menu)return;
  const show=typeof forceState==='boolean'?forceState:menu.classList.contains('hidden'); menu.classList.toggle('hidden',!show);
}
document.addEventListener('click',event=>{const menu=document.getElementById('header-overflow-menu');if(menu&&!menu.classList.contains('hidden')&&!event.target.closest('#header-overflow-menu')&&!event.target.closest('.header-actions'))toggleHeaderMenu(false);});
function toggleQuickActions(forceState){const sheet=document.getElementById('quick-action-sheet'),backdrop=document.getElementById('quick-action-backdrop');if(!sheet||!backdrop)return;const show=typeof forceState==='boolean'?forceState:sheet.classList.contains('hidden');sheet.classList.toggle('hidden',!show);backdrop.classList.toggle('hidden',!show);}
function closeQuickActions(){toggleQuickActions(false);}
function quickCreateInvoice(){closeQuickActions();openCreateInvoice();}
function quickCustomerOperation(){closeQuickActions();pendingQuickCustomerOperation=true;switchView('customers');showToast('اختر العميل لفتح نموذج تسجيل الحركة مباشرة','info');}
function loadActivityLog(){if(!window.Android||!window.Android.getActivityLog){const box=document.getElementById('activity-log-list');if(box)box.innerHTML='<div class="empty-state">سجل العمليات غير متاح في هذا الإصدار</div>';return;}const box=document.getElementById('activity-log-list');if(box)box.innerHTML='<div class="empty-state">جارٍ تحميل العمليات المسجلة...</div>';window.Android.getActivityLog();}
window.onActivityLogData=function(jsonStr){try{activityLogData=JSON.parse(jsonStr)||[];renderActivityLog();}catch(e){console.error(e);}};
function renderActivityLog(){const box=document.getElementById('activity-log-list');if(!box)return;const q=(document.getElementById('activity-search')?.value||'').trim().toLocaleLowerCase(),f=document.getElementById('activity-filter')?.value||'ALL';const rows=activityLogData.filter(x=>(f==='ALL'||x.kind===f)&&(!q||[x.title,x.subtitle,x.description,x.partyName,x.invoiceNumber].join(' ').toLocaleLowerCase().includes(q)));const summary=document.getElementById('activity-summary');if(summary)summary.textContent='عدد العمليات المعروضة: '+rows.length+' من '+activityLogData.length+' • الأحدث أولاً';if(!rows.length){box.innerHTML='<div class="empty-state">لا توجد عمليات مطابقة في السجلات الحالية</div>';return;}box.innerHTML=rows.map(x=>{const id=Number(x.recordId ?? x.id),pid=Number(x.partyId||0),itemId=Number(x.itemId||0),kind=x.kind,can=kind!=='STOCK',icon=kind==='INVOICE'?'▤':kind==='CUSTOMER_TX'?'👤':kind==='SUPPLIER_TX'?'♙':'▦';return `<article class="activity-item"><div class="activity-kind-icon">${icon}</div><div class="activity-main" onclick="openActivityRecord('${kind}',${id},${pid},'${x.partyType||''}',${Number(x.adjustmentId||0)},${itemId})"><div class="activity-title-row"><span class="activity-title">${escapeHtml(x.title||'عملية مسجلة')}</span><span class="activity-date">${fmtShortDate(x.date)}</span></div><div class="activity-subtitle">${escapeHtml(x.subtitle||x.description||'')}</div>${Number(x.amount||0)>0?'<div class="activity-amount">'+fmtMoney(x.amount)+' '+escapeHtml(appCurrency)+'</div>':''}</div><button class="activity-menu-btn" type="button" onclick="toggleActivityMenu(event,'${kind}-${id}')">⋮</button><div id="activity-menu-${kind}-${id}" class="activity-menu hidden"><button onclick="openActivityRecord('${kind}',${id},${pid},'${x.partyType||''}',${Number(x.adjustmentId||0)},${itemId})">${kind==='STOCK'?'عرض سجل الصنف':'فتح وتعديل'}</button>${can?'<button onclick="activityPrint(\''+kind+'\','+id+')">طباعة سريعة</button><button onclick="activityShare(\''+kind+'\','+id+','+pid+',\''+(x.partyType||'')+'\')">مشاركة واتساب</button>':''}</div><div class="activity-actions"><button onclick="openActivityRecord('${kind}',${id},${pid},'${x.partyType||''}',${Number(x.adjustmentId||0)},${itemId})">${kind==='STOCK'?'عرض الحركات':'فتح السجل'}</button>${can?'<button onclick="activityPrint(\''+kind+'\','+id+')">طباعة</button><button onclick="activityShare(\''+kind+'\','+id+','+pid+',\''+(x.partyType||'')+'\')">واتساب</button>':''}</div></article>`;}).join('');}
function toggleActivityMenu(event,key){event.stopPropagation();document.querySelectorAll('.activity-menu').forEach(m=>{if(m.id!=='activity-menu-'+key)m.classList.add('hidden');});document.getElementById('activity-menu-'+key)?.classList.toggle('hidden');}
function openActivityRecord(kind,id,partyId,partyType,adjustmentId,itemId){if(kind==='INVOICE'){openEditInvoice(id);return;}if(kind==='CUSTOMER_TX'){pendingActivityTxToEdit=id;pendingActivityPartyType='CUSTOMER';openCustomerDetail(partyId);return;}if(kind==='SUPPLIER_TX'){pendingActivityTxToEdit=id;pendingActivityPartyType='SUPPLIER';openSupplierDetail(partyId);return;}if(kind==='STOCK'){pendingInventoryAdjustmentId=Number(adjustmentId||id)||0;switchView('inventory');openInventoryHistory(itemId||id);}}
function activityPrint(kind,id){if(kind==='INVOICE')quickThermalPreview(id);else if(kind==='CUSTOMER_TX'||kind==='SUPPLIER_TX')previewTransactionThermal(id);}
function activityShare(kind,id,partyId){if(kind==='INVOICE')quickShareImage(id);else if((kind==='CUSTOMER_TX'||kind==='SUPPLIER_TX')&&window.Android&&window.Android.shareTransactionWhatsApp)window.Android.shareTransactionWhatsApp(id,nextReqId());}

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

    const lowThreshold = Math.max(1, Number(data.lowStockThreshold ?? settingsData.lowStockThreshold) || 10);
    const lowBox = document.getElementById('dash-low-stock-alert');
    if ((data.lowStockCount || 0) > 0) {
      lowBox.textContent = `تنبيه المخزون: يوجد ${data.lowStockCount} أصناف كميتها منخفضة (≤ ${lowThreshold}) — اضغط للعرض`;
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
          <div class="card compact-card" style="margin-bottom:5px; padding:8px;">
            <div class="list-item" style="border:0; padding:2px 0;" onclick="openEditInvoice(${inv.id})">
              <div>
                <strong>#${escapeHtml(inv.invoiceNumber)}</strong> ${badge}
                <div class="muted-text">${escapeHtml(inv.partyName)} • ${escapeHtml(invoiceTypeAr(inv.type))}</div>
              </div>
              <div class="list-item-actions">
                <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
                <span class="muted-text" style="font-size:11px;">${fmtShortDate(inv.date)}</span>
              </div>
            </div>
            <div class="btn-row-compact" style="margin-top:5px;">
              <button type="button" class="tbl-action-btn" onclick="event.stopPropagation(); openEditInvoice(${inv.id})">تعديل</button>
              <button type="button" class="tbl-action-btn del" onclick="event.stopPropagation(); deleteInvoiceWithConfirmation(${inv.id})">حذف</button>
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
    refreshContextSuggestions();
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
  const showZero = settingsData.showZeroBalanceParties !== false;
  // Newest customer first. Auto-increment Room IDs preserve insertion order,
  // including after app restarts and backup restoration.
  const filtered = customersData.filter(c => {
    const matchSearch = !query || c.name.toLowerCase().includes(query) || (c.phone || '').includes(query);
    if (!matchSearch) return false;
    if (customerFilter === 'DEBIT') return c.balance > 0.01;
    if (customerFilter === 'CREDIT') return c.balance < -0.01;
    if (customerFilter === 'SETTLED') return Math.abs(c.balance) <= 0.01;
    if (!showZero && Math.abs(c.balance) <= 0.01) return false;
    return true;
  }).sort((a, b) => (Number(b.id) || 0) - (Number(a.id) || 0));

  const totalDebt = filtered.reduce((sum, c) => sum + Math.max(c.balance, 0), 0);
  summary.innerHTML = `<span>العملاء: ${filtered.length}</span><span>إجمالي الديون: ${fmtMoney(totalDebt)} ${escapeHtml(appCurrency)}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا يوجد عملاء مطابقون للبحث</div>';
    return;
  }

  container.innerHTML = filtered.map(c => {
    const isDebit = Number(c.balance) > 0.01;
    const isCredit = Number(c.balance) < -0.01;
    const balanceClass = isDebit ? 'customer-balance-debit' : (isCredit ? 'customer-balance-credit' : 'customer-balance-settled');
    const balanceLabel = isDebit ? 'عليه' : (isCredit ? 'له' : 'رصيد مسدد');
    const balanceValue = isDebit ? c.balance : (isCredit ? -c.balance : 0);
    return `
      <article class="customer-ledger-card" onclick="openCustomerDetail(${c.id})" role="button" tabindex="0" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();openCustomerDetail(${c.id})}">
        <div class="customer-ledger-main">
          <div class="customer-ledger-identity">
            <span class="customer-ledger-avatar" aria-hidden="true">${escapeHtml((c.name || '؟').trim().charAt(0) || '؟')}</span>
            <div class="customer-ledger-name-wrap">
              <strong class="customer-ledger-name">${escapeHtml(c.name)}</strong>
              <span class="customer-ledger-caption">عميل</span>
            </div>
          </div>
          <div class="customer-ledger-balance ${balanceClass}">
            <span class="customer-ledger-balance-label">${balanceLabel}</span>
            <strong>${fmtMoney(balanceValue)}</strong>
            <small>${escapeHtml(appCurrency)}</small>
          </div>
        </div>
        <div class="customer-ledger-details">
          <span class="customer-ledger-detail"><span aria-hidden="true">☎</span>${escapeHtml(c.phone || 'بدون جوال')}</span>
          ${c.address ? `<span class="customer-ledger-detail customer-ledger-address"><span aria-hidden="true">⌖</span>${escapeHtml(c.address)}</span>` : ''}
        </div>
        <div class="customer-ledger-footer">
          <span class="customer-ledger-open">فتح كشف الحساب <span aria-hidden="true">←</span></span>
          <div class="customer-ledger-actions">
            <button type="button" class="tbl-action-btn" onclick="event.stopPropagation(); openCustomerForm(${c.id})">تعديل</button>
            <button type="button" class="tbl-action-btn del" onclick="event.stopPropagation(); deleteCustomerFromList(${c.id})">حذف</button>
          </div>
        </div>
      </article>
    `;
  }).join('');
}

function deleteCustomerFromList(customerId) {
  const customer = customersData.find(x => Number(x.id) === Number(customerId));
  if (!customer || !window.Android) return;
  if (!window.confirm(`حذف العميل «${customer.name}»؟ لا يمكن الحذف إذا كان لديه رصيد أو فواتير محفوظة.`)) return;
  window.Android.deleteCustomer(Number(customerId), nextReqId());
}

function importCustomerContact() {
  if (!window.Android || typeof window.Android.pickCustomerContact !== 'function') {
    showToast('ميزة جهات الاتصال غير متاحة في هذه النسخة', 'error');
    return;
  }
  const name = (document.getElementById('cust-form-name').value || '').trim();
  const phone = (document.getElementById('cust-form-phone').value || '').trim();
  if ((name || phone) && !window.confirm('سيتم استبدال الاسم ورقم الهاتف الحاليين ببيانات جهة الاتصال المختارة. هل تريد المتابعة؟')) {
    return;
  }
  window.Android.pickCustomerContact();
}

function openCustomerForm(customerId) {
  editingCustomerId = customerId || 0;
  const isEdit = editingCustomerId > 0;
  document.getElementById('customer-form-title').textContent = isEdit ? 'تعديل بيانات العميل' : 'إضافة عميل جديد';
  const balLabel = document.getElementById('cust-form-balance-label');
  if (balLabel) balLabel.textContent = isEdit ? 'الرصيد الحالي' : 'الرصيد الافتتاحي';

  const balInput = document.getElementById('cust-form-balance');
  balInput.disabled = false;

  const fromDetail = (activeCustomerDetail && activeCustomerDetail.customer.id === editingCustomerId)
    ? activeCustomerDetail.customer
    : null;
  const c = isEdit ? (fromDetail || customersData.find(x => x.id === editingCustomerId)) : null;

  if (c) {
    document.getElementById('cust-form-name').value = c.name || '';
    document.getElementById('cust-form-phone').value = c.phone || '';
    document.getElementById('cust-form-address').value = c.address || '';
    balInput.value = c.balance !== undefined && c.balance !== null ? String(c.balance) : '0';
  } else {
    document.getElementById('cust-form-name').value = '';
    document.getElementById('cust-form-phone').value = '';
    document.getElementById('cust-form-address').value = '';
    balInput.value = '';
  }
  switchView('customer-form');
}

function submitCustomerForm() {
  const name = (document.getElementById('cust-form-name').value || '').trim();
  const phone = (document.getElementById('cust-form-phone').value || '').trim();
  const address = (document.getElementById('cust-form-address').value || '').trim();
  const rawBal = document.getElementById('cust-form-balance').value;
  const balance = rawBal === '' ? 0 : (parseFloat(rawBal) || 0);

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
  document.getElementById('cust-tx-form-title').classList.add('hidden');
  document.getElementById('cust-tx-cancel-edit').classList.add('hidden');
  document.getElementById('customer-delete-confirm').classList.add('hidden');
  document.getElementById('tx-amount').value = '';
  document.getElementById('tx-desc').value = '';
  const txDateEl = document.getElementById('tx-date');
  if (txDateEl) txDateEl.value = toDateTimeLocalValue(Date.now());
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
      : (isCredit ? `له ${fmtMoney(-c.balance)} ${appCurrency}` : `مسدد (0 ${appCurrency})`);

    document.getElementById('customer-info-card').innerHTML = `
      <div class="card-title-row" style="margin-bottom:2px;">
        <div>
          <strong style="font-size:15px; color:var(--dark-header);">${escapeHtml(c.name)}</strong>
          <span class="${cls}" style="font-weight:800; font-size:13px; margin-right:6px;">(${escapeHtml(balDesc)})</span>
          <div class="muted-text" style="font-size:11.5px;">${escapeHtml(c.phone || 'بدون جوال')}${c.address ? ' • ' + escapeHtml(c.address) : ''}</div>
        </div>
        <div class="btn-row-compact">
          <button type="button" class="tbl-action-btn" onclick="openCustomerForm(${c.id})">تعديل</button>
          <button type="button" class="tbl-action-btn del" onclick="toggleCustomerDeleteConfirm(true)">حذف</button>
        </div>
      </div>
    `;

    renderCustomerTransactions();
    renderCustomerInvoices();
    if(pendingActivityPartyType==='CUSTOMER'&&pendingActivityTxToEdit){const id=pendingActivityTxToEdit;pendingActivityTxToEdit=0;pendingActivityPartyType='';startEditCustomerTx(id);}
    if(pendingQuickCustomerOperation){pendingQuickCustomerOperation=false;const amount=document.getElementById('tx-amount');if(amount){amount.focus();amount.scrollIntoView({behavior:'smooth',block:'center'});showToast('أدخل المبلغ والبيان ثم اختر عليه أو له','success');}}
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
    const actionHtml = t.invoiceId
      ? `<button type="button" class="tbl-action-btn" onclick="previewTransactionThermal(${t.id})">طباعة</button>
         <button type="button" class="tbl-action-btn" onclick="openEditInvoice(${t.invoiceId})">فتح الفاتورة</button>`
      : isConfirming
        ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteCustomerTx(${t.id})">تأكيد</button>
           <button type="button" class="tbl-action-btn" onclick="askDeleteCustomerTx(0)">لا</button>`
        : `<button type="button" class="tbl-action-btn" onclick="previewTransactionThermal(${t.id})">طباعة</button>
           <button type="button" class="tbl-action-btn" onclick="startEditCustomerTx(${t.id})">تعديل</button>
           <button type="button" class="tbl-action-btn del" onclick="askDeleteCustomerTx(${t.id})">حذف</button>`;
    return `
      <tr>
        <td class="num-cell ${cls}" style="font-weight:800;">${signLabel} ${fmtMoney(t.amount)}</td>
        <td style="text-align:right;">${escapeHtml(t.description)}</td>
        <td class="num-cell" style="font-weight:700;">${fmtMoney(t.runningBalance)}</td>
        <td class="num-cell" style="font-size:10.5px;">${fmtShortDate(t.date)}</td>
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
    <div class="card compact-card" style="margin-bottom:5px; padding:7px;">
      <div class="list-item" style="border:0; padding:2px 0;" onclick="openEditInvoice(${inv.id})">
        <div>
          <strong>#${escapeHtml(inv.invoiceNumber)}</strong>
          <div class="muted-text">${escapeHtml(invoiceTypeAr(inv.type))} • ${fmtShortDate(inv.date)}</div>
        </div>
        <div class="list-item-actions">
          <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
          <span class="muted-text">متبقي: ${fmtMoney(inv.remainingAmount)}</span>
        </div>
      </div>
      <div class="btn-row-compact" style="margin-top:4px;">
        <button type="button" class="tbl-action-btn" onclick="event.stopPropagation(); openEditInvoice(${inv.id})">تعديل</button>
        <button type="button" class="tbl-action-btn del" onclick="event.stopPropagation(); deleteInvoiceWithConfirmation(${inv.id})">حذف</button>
      </div>
    </div>
  `).join('');
}

function startEditCustomerTx(txId) {
  if (!activeCustomerDetail) return;
  const tx = (activeCustomerDetail.transactions || []).find(t => t.id === txId);
  if (!tx) return;
  editingCustomerTxId = txId;
  const titleEl = document.getElementById('cust-tx-form-title');
  titleEl.textContent = 'تعديل الحركة المحددة (عدّل المبلغ أو البيان أو التاريخ ثم اضغط عليه أو له)';
  titleEl.classList.remove('hidden');
  document.getElementById('tx-amount').value = tx.amount;
  document.getElementById('tx-desc').value = tx.description;
  const txDateEl = document.getElementById('tx-date');
  if (txDateEl) txDateEl.value = toDateTimeLocalValue(tx.date);
  document.getElementById('cust-tx-cancel-edit').classList.remove('hidden');
}

function cancelEditCustomerTx() {
  editingCustomerTxId = 0;
  document.getElementById('cust-tx-form-title').classList.add('hidden');
  document.getElementById('tx-amount').value = '';
  document.getElementById('tx-desc').value = '';
  const txDateEl = document.getElementById('tx-date');
  if (txDateEl) txDateEl.value = toDateTimeLocalValue(Date.now());
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
  const txDateEl = document.getElementById('tx-date');
  const dateMillis = txDateEl ? fromDateTimeLocalValue(txDateEl.value) : 0;
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast('الرجاء إدخال مبلغ صحيح أكبر من صفر', 'error');
    return;
  }
  if (!window.Android || !activeCustomerId) return;

  const isNewCustomerTransaction = editingCustomerTxId <= 0;
  const customerIdForShare = activeCustomerId;
  const reqId = nextReqId(() => {
    cancelEditCustomerTx();
    if (settingsData.promptWhatsAppAfterTx !== false &&
        isNewCustomerTransaction && customerIdForShare > 0 &&
        window.Android && window.Android.shareCustomerWhatsApp &&
        window.confirm('تم تسجيل العملية بنجاح. هل تريد فتح محادثة العميل في واتساب ومشاركة كشف حسابه الآن؟')) {
      window.Android.shareCustomerWhatsApp(customerIdForShare, nextReqId());
    }
  });
  if (editingCustomerTxId > 0) {
    if (window.Android.updateCustomerTransactionWithDate) {
      window.Android.updateCustomerTransactionWithDate(editingCustomerTxId, activeCustomerId, amount, isDebit, desc, dateMillis, reqId);
    } else {
      window.Android.updateCustomerTransaction(editingCustomerTxId, activeCustomerId, amount, isDebit, desc, reqId);
    }
  } else {
    if (window.Android.addCustomerTransactionWithDate) {
      window.Android.addCustomerTransactionWithDate(activeCustomerId, amount, isDebit, desc, dateMillis, reqId);
    } else {
      window.Android.addCustomerTransaction(activeCustomerId, amount, isDebit, desc, reqId);
    }
  }
}

function shareCustomerWhatsApp() {
  if (!window.Android || !activeCustomerId) return;
  window.Android.shareCustomerWhatsApp(activeCustomerId, nextReqId());
}

function previewTransactionThermal(txId) {
  if (!window.Android || !txId) return;
  thermalReturnView = currentView;
  verifiedPreviewSignature = '';
  const btn = document.getElementById('btn-thermal-print-now');
  if (btn) btn.disabled = true;
  window.Android.previewGenericThermal('TX', txId, '', 0, 0, nextReqId());
}

function previewStatementThermal(partyType, partyId) {
  if (!window.Android || !partyId) return;
  thermalReturnView = currentView;
  verifiedPreviewSignature = '';
  const btn = document.getElementById('btn-thermal-print-now');
  if (btn) btn.disabled = true;
  window.Android.previewGenericThermal('STATEMENT', partyId, partyType, 0, 0, nextReqId());
}

function exportStatementPdf(partyType, partyId) {
  if (!window.Android || !partyId) return;
  window.Android.exportStatementPdf(partyType, partyId, nextReqId());
}

function shareStatementWhatsAppPdf(partyType, partyId) {
  if (!window.Android || !partyId) return;
  window.Android.shareStatementWhatsAppPdf(partyType, partyId, nextReqId());
}

// ---------------- Suppliers ----------------

window.onSuppliersData = function (jsonStr) {
  try {
    suppliersData = JSON.parse(jsonStr);
    refreshContextSuggestions();
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
  const showZero = settingsData.showZeroBalanceParties !== false;
  // Match the customer directory: newest supplier first by auto-increment ID.
  const filtered = suppliersData.filter(s => {
    const matches = !query ||
      s.name.toLowerCase().includes(query) ||
      (s.company || '').toLowerCase().includes(query) ||
      (s.phone || '').includes(query);
    if (!matches) return false;
    if (!showZero && !query && Math.abs(s.balance) <= 0.01) return false;
    return true;
  }).sort((a, b) => (Number(b.id) || 0) - (Number(a.id) || 0));

  const totalPayable = filtered.reduce((sum, s) => sum + Math.max(s.balance, 0), 0);
  summary.innerHTML = `<span>الموردون: ${filtered.length}</span><span>المستحقات: ${fmtMoney(totalPayable)} ${escapeHtml(appCurrency)}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا يوجد موردون مسجلون</div>';
    return;
  }

  container.innerHTML = filtered.map(s => {
    const isPayable = Number(s.balance) > 0.01;
    const isReceivable = Number(s.balance) < -0.01;
    const balanceClass = isPayable ? 'customer-balance-debit' : (isReceivable ? 'customer-balance-credit' : 'customer-balance-settled');
    const balanceLabel = isPayable ? 'له' : (isReceivable ? 'لنا' : 'رصيد مسدد');
    const balanceValue = isPayable ? s.balance : (isReceivable ? -s.balance : 0);
    return `
      <article class="customer-ledger-card" onclick="openSupplierDetail(${s.id})" role="button" tabindex="0" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();openSupplierDetail(${s.id})}">
        <div class="customer-ledger-main">
          <div class="customer-ledger-identity">
            <span class="customer-ledger-avatar" aria-hidden="true">${escapeHtml((s.name || '؟').trim().charAt(0) || '؟')}</span>
            <div class="customer-ledger-name-wrap">
              <strong class="customer-ledger-name">${escapeHtml(s.name)}</strong>
              <span class="customer-ledger-caption">${escapeHtml(s.company || 'مورد')}</span>
            </div>
          </div>
          <div class="customer-ledger-balance ${balanceClass}">
            <span class="customer-ledger-balance-label">${balanceLabel}</span>
            <strong>${fmtMoney(balanceValue)}</strong>
            <small>${escapeHtml(appCurrency)}</small>
          </div>
        </div>
        <div class="customer-ledger-details">
          <span class="customer-ledger-detail"><span aria-hidden="true">☎</span>${escapeHtml(s.phone || 'بدون جوال')}</span>
          ${s.company ? `<span class="customer-ledger-detail customer-ledger-address"><span aria-hidden="true">▤</span>${escapeHtml(s.company)}</span>` : ''}
        </div>
        <div class="customer-ledger-footer">
          <span class="customer-ledger-open">فتح كشف المورد <span aria-hidden="true">←</span></span>
          <div class="customer-ledger-actions">
            <button type="button" class="tbl-action-btn" onclick="event.stopPropagation(); openSupplierForm(${s.id})">تعديل</button>
            <button type="button" class="tbl-action-btn del" onclick="event.stopPropagation(); deleteSupplierFromList(${s.id})">حذف</button>
          </div>
        </div>
      </article>
    `;
  }).join('');
}

function deleteSupplierFromList(supplierId) {
  const supplier = suppliersData.find(x => Number(x.id) === Number(supplierId));
  if (!supplier || !window.Android) return;
  if (!window.confirm(`حذف المورد «${supplier.name}»؟ لا يمكن الحذف إذا كانت عليه مستحقات أو فواتير مشتريات محفوظة.`)) return;
  window.Android.deleteSupplier(Number(supplierId), nextReqId());
}

function openSupplierForm(supplierId) {
  editingSupplierId = supplierId || 0;
  const isEdit = editingSupplierId > 0;
  document.getElementById('supplier-form-title').textContent = isEdit ? 'تعديل بيانات المورد' : 'إضافة مورد جديد';
  const balLabel = document.getElementById('supp-form-balance-label');
  if (balLabel) balLabel.textContent = isEdit ? 'الرصيد الحالي' : 'الرصيد الافتتاحي';

  const balInput = document.getElementById('supp-form-balance');
  balInput.disabled = false;

  const fromDetail = (activeSupplierDetail && activeSupplierDetail.supplier.id === editingSupplierId)
    ? activeSupplierDetail.supplier
    : null;
  const s = isEdit ? (fromDetail || suppliersData.find(x => x.id === editingSupplierId)) : null;

  if (s) {
    document.getElementById('supp-form-name').value = s.name || '';
    document.getElementById('supp-form-company').value = s.company || '';
    document.getElementById('supp-form-phone').value = s.phone || '';
    balInput.value = s.balance !== undefined && s.balance !== null ? String(s.balance) : '0';
  } else {
    document.getElementById('supp-form-name').value = '';
    document.getElementById('supp-form-company').value = '';
    document.getElementById('supp-form-phone').value = '';
    balInput.value = '';
  }
  switchView('supplier-form');
}

function submitSupplierForm() {
  const name = (document.getElementById('supp-form-name').value || '').trim();
  const company = (document.getElementById('supp-form-company').value || '').trim();
  const phone = (document.getElementById('supp-form-phone').value || '').trim();
  const rawBal = document.getElementById('supp-form-balance').value;
  const balance = rawBal === '' ? 0 : (parseFloat(rawBal) || 0);

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
  document.getElementById('supp-tx-form-title').classList.add('hidden');
  document.getElementById('supp-tx-cancel-edit').classList.add('hidden');
  document.getElementById('supplier-delete-confirm').classList.add('hidden');
  document.getElementById('supp-tx-amount').value = '';
  document.getElementById('supp-tx-desc').value = '';
  const txDateEl = document.getElementById('supp-tx-date');
  if (txDateEl) txDateEl.value = toDateTimeLocalValue(Date.now());
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
      ? `مستحق له ${fmtMoney(s.balance)} ${appCurrency}`
      : (s.balance < -0.01 ? `رصيد لنا ${fmtMoney(-s.balance)} ${appCurrency}` : `مصفر (0 ${appCurrency})`);

    document.getElementById('supplier-info-card').innerHTML = `
      <div class="card-title-row" style="margin-bottom:2px;">
        <div>
          <strong style="font-size:15px; color:var(--dark-header);">${escapeHtml(s.name)}</strong>
          <span class="${cls}" style="font-weight:800; font-size:13px; margin-right:6px;">(${escapeHtml(balDesc)})</span>
          <div class="muted-text" style="font-size:11.5px;">${escapeHtml(s.company || 'مورد')}${s.phone ? ' • ' + escapeHtml(s.phone) : ''}</div>
        </div>
        <div class="btn-row-compact">
          <button type="button" class="tbl-action-btn" onclick="openSupplierForm(${s.id})">تعديل</button>
          <button type="button" class="tbl-action-btn del" onclick="toggleSupplierDeleteConfirm(true)">حذف</button>
        </div>
      </div>
    `;

    renderSupplierTransactions();
    renderSupplierInvoices();
    if(pendingActivityPartyType==='SUPPLIER'&&pendingActivityTxToEdit){const id=pendingActivityTxToEdit;pendingActivityTxToEdit=0;pendingActivityPartyType='';startEditSupplierTx(id);}
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
    const label = t.isCredit ? 'له' : 'عليه';
    const isConfirming = pendingDeleteSupplierTxId === t.id;
    const actionHtml = t.invoiceId
      ? `<button type="button" class="tbl-action-btn" onclick="previewTransactionThermal(${t.id})">طباعة</button>
         <button type="button" class="tbl-action-btn" onclick="openEditInvoice(${t.invoiceId})">فتح الفاتورة</button>`
      : isConfirming
        ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteSupplierTx(${t.id})">تأكيد</button>
           <button type="button" class="tbl-action-btn" onclick="askDeleteSupplierTx(0)">لا</button>`
        : `<button type="button" class="tbl-action-btn" onclick="previewTransactionThermal(${t.id})">طباعة</button>
           <button type="button" class="tbl-action-btn" onclick="startEditSupplierTx(${t.id})">تعديل</button>
           <button type="button" class="tbl-action-btn del" onclick="askDeleteSupplierTx(${t.id})">حذف</button>`;
    return `
      <tr>
        <td class="num-cell ${cls}" style="font-weight:800;">${label} ${fmtMoney(t.amount)}</td>
        <td style="text-align:right;">${escapeHtml(t.description)}</td>
        <td class="num-cell" style="font-weight:700;">${fmtMoney(t.runningBalance)}</td>
        <td class="num-cell" style="font-size:10.5px;">${fmtShortDate(t.date)}</td>
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
    <div class="card compact-card" style="margin-bottom:5px; padding:7px;">
      <div class="list-item" style="border:0; padding:2px 0;" onclick="openEditInvoice(${inv.id})">
        <div>
          <strong>#${escapeHtml(inv.invoiceNumber)}</strong>
          <div class="muted-text">${fmtShortDate(inv.date)}</div>
        </div>
        <div class="list-item-actions">
          <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
          <span class="muted-text">متبقي: ${fmtMoney(inv.remainingAmount)}</span>
        </div>
      </div>
      <div class="btn-row-compact" style="margin-top:4px;">
        <button type="button" class="tbl-action-btn" onclick="event.stopPropagation(); openEditInvoice(${inv.id})">تعديل</button>
        <button type="button" class="tbl-action-btn del" onclick="event.stopPropagation(); deleteInvoiceWithConfirmation(${inv.id})">حذف</button>
      </div>
    </div>
  `).join('');
}

function startEditSupplierTx(txId) {
  if (!activeSupplierDetail) return;
  const tx = (activeSupplierDetail.transactions || []).find(t => t.id === txId);
  if (!tx) return;
  editingSupplierTxId = txId;
  const titleEl = document.getElementById('supp-tx-form-title');
  titleEl.textContent = 'تعديل حركة المورد (عدّل المبلغ أو البيان أو التاريخ ثم اضغط له أو عليه)';
  titleEl.classList.remove('hidden');
  document.getElementById('supp-tx-amount').value = tx.amount;
  document.getElementById('supp-tx-desc').value = tx.description;
  const txDateEl = document.getElementById('supp-tx-date');
  if (txDateEl) txDateEl.value = toDateTimeLocalValue(tx.date);
  document.getElementById('supp-tx-cancel-edit').classList.remove('hidden');
}

function cancelEditSupplierTx() {
  editingSupplierTxId = 0;
  document.getElementById('supp-tx-form-title').classList.add('hidden');
  document.getElementById('supp-tx-amount').value = '';
  document.getElementById('supp-tx-desc').value = '';
  const txDateEl = document.getElementById('supp-tx-date');
  if (txDateEl) txDateEl.value = toDateTimeLocalValue(Date.now());
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
  const txDateEl = document.getElementById('supp-tx-date');
  const dateMillis = txDateEl ? fromDateTimeLocalValue(txDateEl.value) : 0;
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast('الرجاء إدخال مبلغ صحيح أكبر من صفر', 'error');
    return;
  }
  if (!window.Android || !activeSupplierId) return;
  const isNewSupplierTransaction = editingSupplierTxId <= 0;
  const supplierIdForShare = activeSupplierId;
  const reqId = nextReqId(() => {
    cancelEditSupplierTx();
    if (settingsData.promptWhatsAppAfterTx !== false &&
        isNewSupplierTransaction && supplierIdForShare > 0 &&
        window.Android && window.Android.shareSupplierWhatsApp &&
        window.confirm('تم تسجيل العملية بنجاح. هل تريد فتح محادثة المورد في واتساب ومشاركة كشف حسابه الآن؟')) {
      window.Android.shareSupplierWhatsApp(supplierIdForShare, nextReqId());
    }
  });
  if (editingSupplierTxId > 0) {
    if (window.Android.updateSupplierTransactionWithDate) {
      window.Android.updateSupplierTransactionWithDate(editingSupplierTxId, activeSupplierId, amount, isCredit, desc, dateMillis, reqId);
    } else {
      window.Android.updateSupplierTransaction(editingSupplierTxId, activeSupplierId, amount, isCredit, desc, reqId);
    }
  } else {
    if (window.Android.addSupplierTransactionWithDate) {
      window.Android.addSupplierTransactionWithDate(activeSupplierId, amount, isCredit, desc, dateMillis, reqId);
    } else {
      window.Android.addSupplierTransaction(activeSupplierId, amount, isCredit, desc, reqId);
    }
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
    refreshUnitSelectOptions(document);
    refreshContextSuggestions();
    populateInventoryCategoryFilter();
    populateInventoryCategorySuggestions();
    if (currentView === 'inventory') renderInventoryList();
    if (currentView === 'create-invoice') {
      populateItemDropdown();
      const hasActiveEntry = batchEntryRows.some(r =>
        (r.itemName && r.itemName.trim() !== '') || r.total !== '' || r.unitPrice !== ''
      ) || Boolean(document.querySelector('#invoice-batch-rows input:focus'));
      if (!hasActiveEntry) {
        renderBatchEntryRows();
      } else {
        batchEntryRows.forEach((_, idx) => updateBatchUnitOptions(idx));
      }
    }
  } catch (e) {
    console.error(e);
  }
};

function populateInventoryCategoryFilter() {
  const select = document.getElementById('inventory-category-filter');
  if (!select) return;
  const current = inventoryCategoryFilter;
  const categories = [...new Set(inventoryData.map(i => (i.category || 'عام').trim()).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'ar'));
  select.innerHTML = '<option value="ALL">كل الفئات</option>' +
    categories.map(c => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join('');
  select.value = categories.includes(current) ? current : 'ALL';
  inventoryCategoryFilter = select.value;
}

function populateInventoryCategorySuggestions() {
  const list = document.getElementById('inventory-categories');
  if (!list) return;
  const categories = [...new Set(inventoryData.map(i => (i.category || '').trim()).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'ar'));
  list.innerHTML = categories.map(c => `<option value="${escapeHtml(c)}"></option>`).join('');
}

function setInventoryCategory(value) {
  inventoryCategoryFilter = value || 'ALL';
  renderInventoryList();
}

function setInventorySort(value) {
  inventorySort = value || 'NAME';
  renderInventoryList();
}

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
  const searchEl = document.getElementById('inventory-search');
  if (!container || !summary || !searchEl) return;
  const query = (searchEl.value || '').trim().toLowerCase();
  const lowThreshold = Math.max(1, Number(settingsData.lowStockThreshold) || 10);
  const lowPill = document.getElementById('inventory-low-pill');
  if (lowPill) lowPill.textContent = `منخفض (≤ ${lowThreshold})`;

  const filtered = inventoryData.filter(i => {
    const name = (i.name || '').toLowerCase();
    const category = (i.category || 'عام').toLowerCase();
    const matchText = !query || name.includes(query) || category.includes(query);
    if (!matchText) return false;
    if (inventoryCategoryFilter !== 'ALL' && (i.category || 'عام') !== inventoryCategoryFilter) return false;
    if (inventoryFilter === 'LOW') return Number(i.quantity) > 0 && Number(i.quantity) <= lowThreshold;
    if (inventoryFilter === 'OUT') return Number(i.quantity) <= 0;
    return true;
  });

  filtered.sort((a, b) => {
    if (inventorySort === 'QTY_ASC') return Number(a.quantity) - Number(b.quantity);
    if (inventorySort === 'QTY_DESC') return Number(b.quantity) - Number(a.quantity);
    if (inventorySort === 'VALUE_DESC') return (Number(b.quantity) * Number(b.purchasePrice)) - (Number(a.quantity) * Number(a.purchasePrice));
    return (a.name || '').localeCompare(b.name || '', 'ar');
  });

  const totalCost = filtered.reduce((sum, i) => sum + (Number(i.quantity) * Number(i.purchasePrice)), 0);
  const totalRetail = filtered.reduce((sum, i) => sum + (Number(i.quantity) * Number(i.sellingPrice)), 0);
  const totalUnits = filtered.reduce((sum, i) => sum + (Number(i.quantity) || 0), 0);
  const lowCount = filtered.filter(i => Number(i.quantity) > 0 && Number(i.quantity) <= lowThreshold).length;
  const outCount = filtered.filter(i => Number(i.quantity) <= 0).length;
  summary.innerHTML = `<span>الأصناف: ${filtered.length}</span><span>الوحدات: ${fmtMoney(totalUnits)}</span><span>قيمة الشراء: ${fmtMoney(totalCost)} ${escapeHtml(appCurrency)}</span><span>قيمة البيع: ${fmtMoney(totalRetail)} ${escapeHtml(appCurrency)}</span><span>منخفض: ${lowCount}</span><span>نافذ: ${outCount}</span>`;

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">لا توجد أصناف مطابقة للبحث</div>';
    return;
  }

  container.innerHTML = filtered.map(i => {
    const qty = Number(i.quantity) || 0;
    const badge = qty <= 0
      ? '<span class="badge badge-red">نافذ</span>'
      : (qty <= lowThreshold ? '<span class="badge badge-amber">منخفض</span>' : '<span class="badge badge-green">متوفر</span>');
    const isConfirming = pendingDeleteInventoryId === i.id;
    const actionsHtml = isConfirming
      ? `<button type="button" class="tbl-action-btn del" onclick="confirmDeleteInventory(${i.id})">تأكيد الحذف</button>
         <button type="button" class="tbl-action-btn" onclick="askDeleteInventory(0)">تراجع</button>`
      : `<button type="button" class="tbl-action-btn" onclick="openInventoryHistory(${i.id})">الحركات</button>
         <button type="button" class="tbl-action-btn" onclick="openInventoryForm(${i.id})">تعديل</button>
         <button type="button" class="tbl-action-btn del" onclick="askDeleteInventory(${i.id})">حذف</button>`;

    return `
      <article class="inventory-row">
        <div class="inventory-row-main">
          <div class="inventory-item-title"><strong>${escapeHtml(i.name)}</strong>${badge}</div>
          <span class="inventory-item-sub">${escapeHtml(i.category || 'عام')} · ${escapeHtml(parseInventoryUnitConfig(i.unit).base || 'حبة')}</span>
        </div>
        <div class="inventory-row-qty">
          <span class="inventory-meta-label">الرصيد</span>
          <strong class="${qty <= 0 ? 'text-danger' : (qty <= lowThreshold ? 'text-primary' : 'text-success')}">${fmtMoney(qty)}</strong>
        </div>
        <div class="inventory-row-prices">
          <span>شراء <b>${fmtMoney(i.purchasePrice)}</b></span>
          <span>بيع <b>${fmtMoney(i.sellingPrice)}</b></span>
        </div>
        <div class="inventory-row-actions">${actionsHtml}</div>
      </article>
    `;
  }).join('');
}

function toggleInventoryQuickAdd(forceState) {
  const panel = document.getElementById('inventory-quick-add');
  if (!panel) return;
  const show = typeof forceState === 'boolean' ? forceState : panel.classList.contains('hidden');
  panel.classList.toggle('hidden', !show);
  if (show) {
    refreshUnitSelectOptions(document);
    const cat = document.getElementById('inventory-quick-category');
    const unit = document.getElementById('inventory-quick-unit');
    if (cat && !cat.value) cat.value = settingsData.defaultInventoryCategory || 'مواد غذائية';
    if (unit && !unit.value) unit.value = settingsData.defaultInventoryUnit || 'حبة';
    updateInventoryUnitPreview('inventory-quick');
    const name = document.getElementById('inventory-quick-name');
    if (name) name.focus();
  }
}

function submitInventoryQuickAdd() {
  const name = (document.getElementById('inventory-quick-name').value || '').trim();
  const category = (document.getElementById('inventory-quick-category').value || '').trim() || settingsData.defaultInventoryCategory || 'مواد غذائية';
  const unit = serializeInventoryUnitConfig('inventory-quick');
  if (!unit) return;
  const quantityRaw = document.getElementById('inventory-quick-qty').value;
  const buyRaw = document.getElementById('inventory-quick-buy').value;
  const sellRaw = document.getElementById('inventory-quick-sell').value;
  const quantity = quantityRaw === '' ? 0 : Number(quantityRaw);
  const purchasePrice = buyRaw === '' ? 0 : Number(buyRaw);
  const sellingPrice = sellRaw === '' ? 0 : Number(sellRaw);
  if (!name) {
    showToast('أدخل اسم الصنف', 'error');
    document.getElementById('inventory-quick-name').focus();
    return;
  }
  if (inventoryData.some(i => (i.name || '').trim().toLocaleLowerCase() === name.toLocaleLowerCase())) {
    showToast('هذا الصنف موجود مسبقاً؛ استخدم تعديل الصنف الموجود', 'error');
    return;
  }
  if (!Number.isInteger(quantity) || quantity < 0 || !Number.isFinite(purchasePrice) || purchasePrice < 0 ||
      !Number.isFinite(sellingPrice) || sellingPrice < 0) {
    showToast('تحقق من الكمية وأسعار الشراء والبيع', 'error');
    return;
  }
  if (!window.Android) return;
  const reqId = nextReqId((created) => {
    if (!created || !created.id) return;
    ['name','qty','buy','sell','category','unit','pack-name','pack-size','carton-name','carton-packs'].forEach(key => {
      const el = document.getElementById('inventory-quick-' + key);
      if (el) el.value = '';
    });
    toggleInventoryQuickAdd(false);
    showToast('تمت إضافة الصنف إلى المخزن ✓', 'success');
    renderInventoryList();
  });
  window.Android.saveInventoryItem(JSON.stringify({
    id: 0, name, category, unit, quantity, purchasePrice, sellingPrice
  }), reqId);
}

function openInventoryHistory(itemId) {
  if (!window.Android || !itemId) return;
  activeInventoryHistoryItemId = Number(itemId) || 0;
  window.Android.getInventoryHistory(itemId, nextReqId());
}

function saveInventoryAdjustment(adjustmentId) {
  const quantity = Number(document.getElementById('inventory-adjustment-qty-' + adjustmentId)?.value);
  const reason = String(document.getElementById('inventory-adjustment-reason-' + adjustmentId)?.value || '').trim();
  if (!Number.isInteger(quantity) || quantity < 0 || quantity > 10000000 || !reason) {
    showToast('أدخل كمية صحيحة وسبباً للتسوية', 'error');
    return;
  }
  if (!window.Android?.updateInventoryAdjustment) return;
  const reqId = nextReqId(() => {
    if (activeInventoryHistoryItemId) window.Android.getInventoryHistory(activeInventoryHistoryItemId, nextReqId());
  });
  window.Android.updateInventoryAdjustment(adjustmentId, quantity, reason, reqId);
}

function toggleInventoryAdjustmentDeleteConfirm(adjustmentId, show) {
  document.getElementById('inventory-adjustment-delete-confirm-' + adjustmentId)?.classList.toggle('hidden', !show);
}

function deleteInventoryAdjustment(adjustmentId) {
  if (!window.Android?.deleteInventoryAdjustment) return;
  const reqId = nextReqId(() => {
    if (activeInventoryHistoryItemId) window.Android.getInventoryHistory(activeInventoryHistoryItemId, nextReqId());
  });
  window.Android.deleteInventoryAdjustment(adjustmentId, reqId);
}

function closeInventoryHistory() {
  const box = document.getElementById('inventory-history-card');
  if (box) box.classList.add('hidden');
}

function exportInventoryPdf() {
  if (!window.Android) return;
  window.Android.exportInventoryPdf(nextReqId());
}

window.onInventoryHistoryData = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const box = document.getElementById('inventory-history-card');
    if (!box) return;
    const item = data.item;
    const movements = data.movements || [];
    const rowsHtml = movements.length === 0
      ? '<div class="muted-text" style="text-align:center; padding:6px;">لا توجد حركات فواتير مسجلة لهذا الصنف</div>'
      : `<div class="table-wrap" style="margin-top:6px;"><table class="data-table ruled-table"><thead><tr><th>الفاتورة</th><th>الطرف</th><th>الكمية</th><th>السعر</th><th>التاريخ</th><th>إجراء</th></tr></thead><tbody>` +
        movements.map(m => {
          const isAdjustment = m.invoiceType === 'ADJUSTMENT';
          const isAdd = m.deltaQty > 0;
          const cls = isAdd ? 'text-success' : 'text-danger';
          const qtyStr = isAdd ? `+${m.deltaQty}` : String(m.deltaQty);
          const invoiceCell = isAdjustment ? '<strong>تسوية مخزون</strong>' : `<strong>#${escapeHtml(m.invoiceNumber)}</strong>`;
          const actions = isAdjustment ? `<div style="display:grid;gap:3px;min-width:112px;"><input type="number" min="0" step="1" id="inventory-adjustment-qty-${m.adjustmentId}" value="${Number(m.newQuantity ?? 0)}" aria-label="الكمية الجديدة للتسوية" style="width:100%;min-width:0;padding:4px;"><input type="text" id="inventory-adjustment-reason-${m.adjustmentId}" value="${escapeHtml(m.partyName)}" aria-label="سبب التسوية" style="width:100%;min-width:0;padding:4px;"><button type="button" class="tbl-action-btn" onclick="saveInventoryAdjustment(${m.adjustmentId})">حفظ التعديل</button><button type="button" class="tbl-action-btn del" onclick="toggleInventoryAdjustmentDeleteConfirm(${m.adjustmentId},true)">حذف التسوية</button><div id="inventory-adjustment-delete-confirm-${m.adjustmentId}" class="hidden"><small>تأكيد حذف هذه التسوية فقط؟</small><button type="button" class="tbl-action-btn del" onclick="deleteInventoryAdjustment(${m.adjustmentId})">تأكيد الحذف</button><button type="button" class="tbl-action-btn" onclick="toggleInventoryAdjustmentDeleteConfirm(${m.adjustmentId},false)">إلغاء</button></div></div>` : `<button type="button" class="tbl-action-btn" onclick="openEditInvoice(${m.invoiceId})">تعديل</button><button type="button" class="tbl-action-btn del" onclick="deleteInvoiceWithConfirmation(${m.invoiceId})">حذف</button>`;
          return `<tr ${isAdjustment ? `id="inventory-adjustment-${Number(m.adjustmentId||0)}"` : ''}>
            <td ${isAdjustment ? '' : `onclick="openEditInvoice(${m.invoiceId})"`}>${invoiceCell}</td>
            <td>${escapeHtml(m.partyName)}</td>
            <td class="num-cell ${cls}" style="font-weight:800;">${qtyStr}</td>
            <td class="num-cell">${fmtMoney(m.unitPrice)}</td>
            <td class="num-cell" style="font-size:10.5px;">${fmtShortDate(m.date)}</td>
            <td style="white-space:nowrap;">${actions}</td>
          </tr>`;
        }).join('') +
        `</tbody></table></div>`;

    box.innerHTML = `
      <div class="card-title-row" style="margin-bottom:4px;">
        <strong>حركات الصنف: ${escapeHtml(item.name)} (الحالي: ${item.quantity})</strong>
        <button type="button" class="link-btn" onclick="closeInventoryHistory()">إغلاق</button>
      </div>
      <div class="summary-strip" style="margin-bottom:4px;">
        <span>الوارد (+): ${data.totalPurchased || 0}</span>
        <span>المباع (-): ${data.totalSold || 0}</span>
        <span>تسويات (+/-): ${data.totalAdjusted || 0}</span>
        <span>التكلفة الحالية: ${fmtMoney(item.purchasePrice)}</span>
      </div>
      ${rowsHtml}
    `;
    box.classList.remove('hidden');
    if (pendingInventoryAdjustmentId) {
      const selectedAdjustment = document.getElementById('inventory-adjustment-' + pendingInventoryAdjustmentId);
      if (selectedAdjustment) {
        selectedAdjustment.style.outline = '2px solid var(--primary)';
        selectedAdjustment.scrollIntoView({ behavior: 'smooth', block: 'center' });
      } else {
        showToast('تم فتح سجل الصنف، لكن سجل التسوية المحدد غير موجود ضمن الحركات المعروضة', 'warning');
      }
      pendingInventoryAdjustmentId = 0;
    }
    const scroll = document.getElementById('main-scroll');
    if (scroll) scroll.scrollTop = 0;
  } catch (e) {
    console.error(e);
  }
};

function openInventoryForm(itemId) {
  refreshUnitSelectOptions(document);
  editingInventoryId = itemId || 0;
  const isEdit = editingInventoryId > 0;
  document.getElementById('inventory-form-title').textContent = isEdit ? 'تعديل بيانات الصنف' : 'إضافة صنف جديد للمخزن';

  if (isEdit) {
    const item = inventoryData.find(x => x.id === editingInventoryId);
    if (item) {
      document.getElementById('inv-item-form-name').value = item.name || '';
      document.getElementById('inv-item-form-category').value = item.category || '';
      fillInventoryUnitConfig('inv-item-form', item.unit || 'حبة');
      document.getElementById('inv-item-form-qty').value = item.quantity;
      document.getElementById('inv-item-form-buy').value = item.purchasePrice;
      document.getElementById('inv-item-form-sell').value = item.sellingPrice;
      document.getElementById('inv-item-form-total-buy').value = item.quantity > 0 ? Math.round(item.quantity * item.purchasePrice * 100) / 100 : '';
    }
  } else {
    document.getElementById('inv-item-form-name').value = '';
    document.getElementById('inv-item-form-category').value = settingsData.defaultInventoryCategory || 'مواد غذائية';
    fillInventoryUnitConfig('inv-item-form', settingsData.defaultInventoryUnit || 'حبة');
    document.getElementById('inv-item-form-qty').value = '';
    document.getElementById('inv-item-form-buy').value = '';
    document.getElementById('inv-item-form-sell').value = '';
    document.getElementById('inv-item-form-total-buy').value = '';
  }
  updateMarginPreview();
  switchView('inventory-form');
}

function syncInventoryBuyFromTotal() {
  const total = parseFloat(document.getElementById('inv-item-form-total-buy').value);
  const qty = parseInt(document.getElementById('inv-item-form-qty').value, 10) || 1;
  if (Number.isFinite(total) && total >= 0 && qty > 0) {
    document.getElementById('inv-item-form-buy').value = Math.round((total / qty) * 10000) / 10000;
  }
  updateMarginPreview();
}

function syncInventoryUnitFromBuyTotal() {
  const total = parseFloat(document.getElementById('inv-item-form-total-buy').value);
  const qty = parseInt(document.getElementById('inv-item-form-qty').value, 10);
  if (Number.isFinite(total) && total > 0 && Number.isFinite(qty) && qty > 0) {
    document.getElementById('inv-item-form-buy').value = Math.round((total / qty) * 10000) / 10000;
  }
  updateMarginPreview();
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
  const category = (document.getElementById('inv-item-form-category').value || '').trim() || settingsData.defaultInventoryCategory || 'مواد غذائية';
  const unit = serializeInventoryUnitConfig('inv-item-form');
  if (!unit) return;
  const rawQty = document.getElementById('inv-item-form-qty').value;
  const quantity = rawQty === '' ? 0 : parseInt(rawQty, 10);
  const rawBuy = document.getElementById('inv-item-form-buy').value;
  const rawSell = document.getElementById('inv-item-form-sell').value;
  const purchasePrice = rawBuy === '' ? 0 : parseFloat(rawBuy);
  const sellingPrice = rawSell === '' ? 0 : parseFloat(rawSell);

  if (!name) {
    showToast('الرجاء إدخال اسم الصنف', 'error');
    document.getElementById('inv-item-form-name').focus();
    return;
  }
  const duplicate = inventoryData.find(i => i.id !== editingInventoryId && (i.name || '').trim().toLocaleLowerCase() === name.toLocaleLowerCase());
  if (duplicate) {
    showToast('هذا الصنف مسجل مسبقاً؛ افتح الصنف الموجود للتعديل', 'error');
    document.getElementById('inv-item-form-name').focus();
    return;
  }
  if (!Number.isFinite(quantity) || quantity < 0 || !Number.isFinite(purchasePrice) || purchasePrice < 0 || !Number.isFinite(sellingPrice) || sellingPrice < 0) {
    showToast('تأكد من صحة الكمية والأسعار', 'error');
    return;
  }
  if (!window.Android) return;

  const payload = { id: editingInventoryId, name, category, unit, quantity, purchasePrice, sellingPrice };
  const reqId = nextReqId(() => switchView('inventory', false));
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

function exportPurchaseInvoicesToInventory() {
  if (!window.Android || invoiceFilter !== 'PURCHASE') return;
  const pending = invoicesData.filter(inv => inv.type === 'PURCHASE').length;
  if (pending === 0) {
    showToast('لا توجد فواتير شراء لترحيلها', 'info');
    return;
  }
  if (!window.confirm('سيتم فحص جميع فواتير الشراء وترحيل الأصناف غير المرتبطة بالمخزن فقط. الأصناف المضافة سابقاً لن تتكرر. هل تريد المتابعة؟')) return;
  const button = document.querySelector('#purchase-bulk-export-row button');
  if (button) button.disabled = true;
  const reqId = nextReqId(() => {
    if (button) button.disabled = false;
  });
  window.Android.exportPurchaseInvoicesToInventory(reqId);
}

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

  const bulkExportRow = document.getElementById('purchase-bulk-export-row');
  if (bulkExportRow) bulkExportRow.classList.toggle('hidden', invoiceFilter !== 'PURCHASE' || !invoicesData.some(inv => inv.type === 'PURCHASE'));

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
      : (inv.status === 'PARTIAL' ? '<span class="badge badge-amber">جزئي</span>' : '<span class="badge badge-red">آجل</span>');
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
        <div class="card-title-row" style="margin-bottom:3px;">
          <div>
            <strong>فاتورة #${escapeHtml(inv.invoiceNumber)}</strong> ${statusBadge}
            <div class="muted-text">${escapeHtml(inv.partyName)} • ${escapeHtml(invoiceTypeAr(inv.type))} • ${fmtShortDate(inv.date)}</div>
          </div>
          <div class="list-item-actions">
            <strong>${fmtMoney(inv.totalAmount)} ${escapeHtml(appCurrency)}</strong>
            <span class="muted-text" style="font-size:11px;">متبقي: ${fmtMoney(inv.remainingAmount)}</span>
          </div>
        </div>
        <div class="btn-row-compact">${footerActions}</div>
      </div>
    `;
  }).join('');
}

function askDeleteInvoice(id) {
  if (id > 0 && settingsData.confirmBeforeDeleteInvoice === false) {
    confirmDeleteInvoice(id);
    return;
  }
  pendingDeleteInvoiceId = id;
  renderInvoicesList();
}

function confirmDeleteInvoice(id) {
  if (!window.Android) return;
  const reqId = nextReqId(() => { pendingDeleteInvoiceId = 0; });
  window.Android.deleteInvoice(id, reqId);
}

// Reuse invoice edit/delete actions anywhere an invoice is referenced.
// The native engine reverses the invoice's stock and account effects atomically.
function deleteInvoiceWithConfirmation(id) {
  if (!window.Android || !Number(id)) return;
  if (settingsData.confirmBeforeDeleteInvoice !== false &&
      !window.confirm('هل تريد حذف هذه الفاتورة نهائياً؟ سيتم عكس أثرها على المخزون ورصيد العميل أو المورد.')) return;
  const view = currentView;
  const customerId = activeCustomerId;
  const supplierId = activeSupplierId;
  const reqId = nextReqId(() => {
    if (view === 'customer-detail' && customerId && window.Android.getCustomerDetail) {
      window.Android.getCustomerDetail(customerId);
    } else if (view === 'supplier-detail' && supplierId && window.Android.getSupplierDetail) {
      window.Android.getSupplierDetail(supplierId);
    } else if (view === 'inventory' && activeInventoryHistoryItemId && window.Android.getInventoryHistory) {
      window.Android.getInventoryHistory(activeInventoryHistoryItemId, nextReqId());
    } else if (view === 'customer-operation' && customerId && window.Android.getCustomerDetail) {
      window.Android.getCustomerDetail(customerId);
    }
    pendingDeleteInvoiceId = 0;
  });
  window.Android.deleteInvoice(Number(id), reqId);
}

function quickThermalPreview(id) {
  if (!window.Android) return;
  thermalReturnView = currentView;
  activePreviewInvoiceId = id;
  verifiedPreviewSignature = '';
  const btn = document.getElementById('btn-thermal-print-now');
  if (btn) btn.disabled = true;
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

function parseInventoryUnitConfig(raw) {
  const legacy = String(raw || '').trim() || 'حبة';
  try {
    const v = JSON.parse(legacy);
    if (v && typeof v === 'object' && v.base) return {
      base: String(v.base), packName: String(v.packName || ''),
      packSize: Math.max(1, parseInt(v.packSize, 10) || 1),
      cartonName: String(v.cartonName || ''),
      cartonPacks: Math.max(1, parseInt(v.cartonPacks, 10) || 1)
    };
  } catch (_) {}
  return { base: legacy, packName: '', packSize: 1, cartonName: '', cartonPacks: 1 };
}
function serializeInventoryUnitConfig(prefix) {
  const get = s => (document.getElementById(prefix + s)?.value || '').trim();
  const base = get('-unit') || 'حبة', packName = get('-pack-name'), packSize = parseInt(get('-pack-size'), 10) || 0;
  const cartonName = get('-carton-name'), cartonPacks = parseInt(get('-carton-packs'), 10) || 0;
  if ((packName && packSize < 1) || (cartonName && cartonPacks < 1)) {
    showToast('أدخل عدد الوحدات داخل العبوة بشكل صحيح', 'error'); return null;
  }
  const configuredNames = [base, packName, cartonName].filter(Boolean).map(normalizeSuggestionText);
  if (new Set(configuredNames).size !== configuredNames.length) {
    showToast('يجب أن يكون لكل مستوى وحدة اسم مختلف', 'error'); return null;
  }
  if (!base.trim()) {
    showToast('حدد أصغر وحدة للمخزون أولاً', 'error'); return null;
  }
  return JSON.stringify({ base, packName, packSize: packName ? packSize : 1, cartonName, cartonPacks: cartonName ? cartonPacks : 1 });
}
function fillInventoryUnitConfig(prefix, raw) {
  const v = parseInventoryUnitConfig(raw);
  const values = { '-unit': v.base, '-pack-name': v.packName, '-pack-size': v.packName ? v.packSize : '',
    '-carton-name': v.cartonName, '-carton-packs': v.cartonName ? v.cartonPacks : '' };
  Object.entries(values).forEach(([s, value]) => { const el = document.getElementById(prefix + s); if (el) { if (el.tagName === 'SELECT' && value && ![...el.options].some(option => option.value === String(value))) { const legacy = document.createElement('option'); legacy.value = String(value); legacy.textContent = String(value); el.appendChild(legacy); } el.value = value; } });
  updateInventoryUnitPreview(prefix);
}
function updateInventoryUnitPreview(prefix) {
  const get = s => (document.getElementById(prefix + s)?.value || '').trim();
  const base = get('-unit') || 'حبة', pack = get('-pack-name'), packSize = parseInt(get('-pack-size'), 10) || 0;
  const carton = get('-carton-name'), cartonPacks = parseInt(get('-carton-packs'), 10) || 0;
  const el = document.getElementById(prefix + '-unit-preview'); if (!el) return;
  const parts = [];
  // Use natural Arabic sentences instead of mixed RTL/LTR equations, which
  // some Android WebViews visually reorder and make the unit relation appear reversed.
  if (carton && cartonPacks > 0) {
    const innerUnit = pack && packSize > 0 ? pack : base;
    const innerCount = pack && packSize > 0 ? packSize : cartonPacks;
    const totalBase = cartonPacks * innerCount;
    parts.push('كل ' + carton + ' يحتوي على ' + cartonPacks + ' ' + innerUnit +
      (pack && packSize > 0 ? '، أي ' + totalBase + ' ' + base : ''));
  }
  if (pack && packSize > 0) parts.push('كل ' + pack + ' يحتوي على ' + packSize + ' ' + base);
  el.textContent = parts.join(' — ') || ('الوحدة الأساسية للمخزون: ' + base);
}
document.addEventListener('input', event => {
  const id = event.target && event.target.id ? event.target.id : '';
  if (id.startsWith('inv-item-form-')) updateInventoryUnitPreview('inv-item-form');
  if (id.startsWith('inventory-quick-') && ['inventory-quick-unit','inventory-quick-pack-name','inventory-quick-pack-size','inventory-quick-carton-name','inventory-quick-carton-packs'].includes(id)) {
    updateInventoryUnitPreview('inventory-quick');
  }
});

function getInventoryUnitOptions(item) {
  if (!item) return [{ name: settingsData.defaultInventoryUnit || 'حبة', factor: 1 }];
  // Inventory quantities and stored purchase cost are always expressed in the
  // smallest stock unit. Invoice rows select a larger unit and carry its
  // exact multiplier back to that smallest unit.
  const v = parseInventoryUnitConfig(item.unit), options = [{ name: v.base, factor: 1 }];
  if (v.packName && v.packSize > 1) options.push({ name: v.packName, factor: v.packSize });
  if (v.cartonName && v.cartonPacks > 0) {
    const factor = v.packName ? v.packSize * v.cartonPacks : v.cartonPacks;
    if (factor > 1) options.push({ name: v.cartonName, factor });
  }
  const invoiceType = document.getElementById('inv-type')?.value || 'CASH';
  return invoiceType === 'PURCHASE' ? options.reverse() : options;
}
function createBlankBatchRow() {
  return { itemId: 0, itemName: '', qty: '1', unitPrice: '', total: '', unitName: '', unitFactor: 1, lastEdited: 'TOTAL' };
}

function setInvoiceSubmitBusy(busy) {
  isSavingInvoice = Boolean(busy);
  const saveBtn = document.getElementById('btn-save-invoice');
  const printBtn = document.getElementById('btn-invoice-preview-print');
  if (saveBtn) saveBtn.disabled = isSavingInvoice;
  if (printBtn) printBtn.disabled = isSavingInvoice;
}

function syncCashPaidWithCart() {
  const type = document.getElementById('inv-type')?.value || 'CASH';
  const paidEl = document.getElementById('inv-paid');
  if (!paidEl) return;
  const subtotal = Math.round(cartItems.reduce((acc, cur) => acc + (Number(cur.totalPrice) || 0), 0) * 100) / 100;
  if (cartItems.length === 0 || subtotal <= 0) {
    paidEl.value = '';
    return;
  }
  if (type === 'CASH' && settingsData.autoFillCashPaid !== false) {
    paidEl.value = String(subtotal);
  } else {
    const currentPaid = parseFloat(paidEl.value);
    if (Number.isFinite(currentPaid) && currentPaid > subtotal) {
      paidEl.value = String(subtotal);
    }
  }
}

function openCreateInvoice(presetType, presetPartyId) {
  editingInvoiceId = 0;
  invoiceDirty = true;
  cartItems = [];
  batchEntryRows = [createBlankBatchRow()];
  setInvoiceSubmitBusy(false);
  document.getElementById('invoice-screen-title').textContent = 'إصدار فاتورة جديدة';
  document.getElementById('btn-save-invoice').textContent = 'حفظ الفاتورة';
  document.getElementById('invoice-saved-badge').classList.add('hidden');
  document.getElementById('inv-type').value = presetType || settingsData.defaultInvoiceType || 'CASH';
  lastInvoiceType = document.getElementById('inv-type').value;
  pendingInvoicePartyId = Number(presetPartyId) || 0;
  document.getElementById('inv-party').value = '0';
  document.getElementById('inv-paid').value = '';
  const invDateEl = document.getElementById('inv-date');
  if (invDateEl) invDateEl.value = toDateTimeLocalValue(Date.now());
  document.getElementById('quick-add-stock-box').classList.add('hidden');
  toggleMultiStockPicker(false);
  toggleInvoiceCartFullscreen(false);
  document.getElementById('exchange-calc-box').classList.add('hidden');

  if (window.Android) {
    window.Android.getCustomers();
    window.Android.getSuppliers();
    window.Android.getInventory();
    window.Android.getNextInvoiceNumber();
  }

  onInvoiceTypeChange();
  if (pendingInvoicePartyId > 0) {
    const sel = document.getElementById('inv-party');
    if (sel && sel.querySelector(`option[value="${pendingInvoicePartyId}"]`)) {
      sel.value = String(pendingInvoicePartyId);
      pendingInvoicePartyId = 0;
      updatePartyBalanceChip();
    }
  }
  renderBatchEntryRows();
  enforceEnglishNumericFields(document.getElementById('view-create-invoice'));
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
  batchEntryRows = [createBlankBatchRow()];
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
    toggleMultiStockPicker(false);
    toggleInvoiceCartFullscreen(false);
    document.getElementById('inv-type').value = inv.type === 'SALE' ? 'CASH' : inv.type;
    lastInvoiceType = document.getElementById('inv-type').value;
    const invDateEl = document.getElementById('inv-date');
    if (invDateEl) invDateEl.value = toDateTimeLocalValue(inv.date);
    onInvoiceTypeChange();
    document.getElementById('inv-party').value = String(inv.partyId || 0);
    document.getElementById('inv-paid').value = inv.paidAmount > 0 ? String(inv.paidAmount) : '';

    cartItems = (data.items || []).map(i => ({
      itemId: i.itemId,
      itemName: i.itemName,
      quantity: i.quantity,
      unitPrice: i.unitPrice,
      totalPrice: i.totalPrice,
      stockQuantity: i.stockQuantity > 0 ? i.stockQuantity : i.quantity,
      unitName: i.unitLabel || '', unitLabel: i.unitLabel || '', unitFactor: i.unitFactor || 1
    }));

    batchEntryRows = [createBlankBatchRow()];
    renderBatchEntryRows();
    populateItemDropdown();
    renderCart();
    updatePartyBalanceChip();
    invoiceDirty = false;
    if (currentView !== 'create-invoice' && currentView !== 'thermal-preview') {
      switchView('create-invoice');
    }
  } catch (e) {
    console.error(e);
  }
};

function setInvoiceType(type) {
  const select = document.getElementById('inv-type');
  if (!select || !['CASH', 'CREDIT', 'PURCHASE'].includes(type)) return;
  if (select.value !== type) { select.value = type; onInvoiceTypeChange(); }
  else updateInvoiceModeUI();
}

function updateInvoiceModeUI() {
  const type = document.getElementById('inv-type').value;
  document.querySelectorAll('.invoice-mode-btn').forEach(btn => {
    const active = btn.dataset.invoiceMode === type;
    btn.classList.toggle('active-cash', active && type === 'CASH');
    btn.classList.toggle('active-credit', active && type === 'CREDIT');
    btn.classList.toggle('active-purchase', active && type === 'PURCHASE');
    btn.setAttribute('aria-pressed', active ? 'true' : 'false');
  });
  const partyCaption = document.getElementById('invoice-party-caption');
  if (partyCaption) partyCaption.textContent = type === 'PURCHASE' ? 'المورد والتاريخ' : 'العميل والتاريخ';
  const addPartyButton = document.querySelector('.invoice-party-toolbar .invoice-tool-btn');
  if (addPartyButton) addPartyButton.textContent = type === 'PURCHASE' ? '+ إضافة مورد' : '+ إضافة عميل';
  const modeLabel = type === 'PURCHASE' ? 'فاتورة شراء'
    : (type === 'CREDIT' ? 'فاتورة بيع آجل' : 'فاتورة بيع نقدي');
  const prefix = editingInvoiceId > 0 ? 'تعديل' : 'إصدار';
  const title = document.getElementById('invoice-screen-title');
  if (title) title.textContent = `${prefix} ${modeLabel}`;
  const badge = document.getElementById('invoice-mode-badge');
  if (badge) {
    badge.textContent = modeLabel;
    badge.className = 'badge ' + (type === 'PURCHASE' ? 'badge-amber' : (type === 'CREDIT' ? 'badge-red' : 'badge-green'));
  }
  const screen = document.getElementById('view-create-invoice');
  if (screen) {
    screen.classList.toggle('invoice-mode-purchase', type === 'PURCHASE');
    screen.classList.toggle('invoice-mode-sale', type !== 'PURCHASE');
  }
  const saveButton = document.getElementById('btn-save-invoice');
  if (saveButton) saveButton.textContent = editingInvoiceId > 0 ? 'حفظ التعديل' : (type === 'PURCHASE' ? 'حفظ فاتورة الشراء' : 'حفظ فاتورة البيع');
  const purchaseHint = document.getElementById('purchase-auto-stock-hint');
  if (purchaseHint) purchaseHint.classList.toggle('hidden', type !== 'PURCHASE');
  const paidInput = document.getElementById('inv-paid');
  if (paidInput) paidInput.placeholder = type === 'PURCHASE' ? 'المدفوع للمورد' : 'المبلغ المستلم';
  const payFullButton = document.querySelector('#inv-paid + button');
  if (payFullButton) payFullButton.textContent = type === 'PURCHASE' ? 'سداد كامل' : 'استلام كامل';
  updateInvoiceTotals();
}

function onInvoiceTypeChange() {
  const type = document.getElementById('inv-type').value;
  const label = document.getElementById('party-label');
  if (lastInvoiceType && (lastInvoiceType === 'PURCHASE') !== (type === 'PURCHASE')) {
    // Customer and supplier IDs belong to separate tables; do not carry them across modes.
    // Cash/credit sales share the same customer list, so keep that selection.
    document.getElementById('inv-party').value = '0';
  }
  lastInvoiceType = type;
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
  updateInvoiceModeUI();
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
  if (pendingInvoicePartyId > 0) {
    document.getElementById('inv-party').value = String(pendingInvoicePartyId);
    pendingInvoicePartyId = 0;
  }
  updatePartyBalanceChip();
}

function populateCustomersDropdown() {
  const select = document.getElementById('inv-party');
  let html = '<option value="0" data-party-name="عميل نقدي">عميل نقدي</option>';
  customersData.forEach(c => {
    html += `<option value="${c.id}" data-party-name="${escapeHtml(c.name)}">${escapeHtml(c.name)} (${fmtMoney(c.balance)})</option>`;
  });
  select.innerHTML = html;

  const quickLimit = Math.max(3, Math.min(12, Number(settingsData.quickPartyCount) || 5));
  const chipsEl = document.getElementById('inv-quick-party-chips');
  chipsEl.innerHTML = customersData.slice(0, quickLimit).map(c =>
    `<button type="button" class="chip chip-blue" onclick="selectQuickParty(${c.id})">${escapeHtml(c.name)}</button>`
  ).join('');
}

function populateSuppliersDropdown() {
  const select = document.getElementById('inv-party');
  let html = '<option value="0" data-party-name="">اختر المورد...</option>';
  suppliersData.forEach(s => {
    html += `<option value="${s.id}" data-party-name="${escapeHtml(s.name)}">${escapeHtml(s.name)}${s.company ? ' - ' + escapeHtml(s.company) : ''}</option>`;
  });
  select.innerHTML = html;

  const quickLimit = Math.max(3, Math.min(12, Number(settingsData.quickPartyCount) || 5));
  const chipsEl = document.getElementById('inv-quick-party-chips');
  chipsEl.innerHTML = suppliersData.slice(0, quickLimit).map(s =>
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

function markInvoiceDirty() {
  invoiceDirty = true;
}

function onInvoicePartyChanged() {
  markInvoiceDirty();
  updatePartyBalanceChip();
}

function populateItemDropdown() {
  const datalist = document.getElementById('inv-items-datalist');
  if (datalist) {
    datalist.innerHTML = inventoryData.map(i =>
      `<option value="${escapeHtml(i.name)}">`
    ).join('');
  }
  const multiBox = document.getElementById('multi-stock-picker-box');
  if (multiBox && !multiBox.classList.contains('hidden')) {
    renderMultiStockPicker();
  }
}

function renderBatchEntryRows() {
  const container = document.getElementById('invoice-batch-rows');
  if (!container) return;
  if (!batchEntryRows || batchEntryRows.length === 0) {
    batchEntryRows = [createBlankBatchRow()];
  }
  container.innerHTML = batchEntryRows.map((row, idx) => `
    <div class="batch-entry-line">
      <div class="ruled-input-row grid-invoice-batch-row">
        <input type="number" id="batch-total-${idx}" class="ruled-cell cell-num batch-total-input" placeholder="الإجمالي" min="0" step="0.01"
          value="${escapeHtml(row.total)}" oninput="onBatchRowTotalInput(${idx}, this.value)">
        <input type="number" id="batch-qty-${idx}" class="ruled-cell cell-num" placeholder="الكمية" min="1" step="1"
          value="${escapeHtml(row.qty)}" oninput="onBatchRowQtyInput(${idx}, this.value)">
        <input type="text" id="batch-name-${idx}" class="ruled-cell" autocomplete="off"
          placeholder="اسم الصنف..." value="${escapeHtml(row.itemName)}"
          oninput="onBatchRowNameInput(${idx}, this.value)" onchange="onBatchRowNameInput(${idx}, this.value)">
        <select id="batch-unit-${idx}" class="ruled-cell batch-unit-select" aria-label="الوحدة أو نوع الوحدة"
          onchange="onBatchRowUnitChange(${idx}, this.value)"></select>
        <button type="button" class="row-remove-cell" onclick="removeBatchRow(${idx})" aria-label="حذف صف">×</button>
      </div>
      <div class="batch-derived-price">
        <label for="batch-price-${idx}">سعر الوحدة (قابل للتعديل)</label>
        <input type="number" id="batch-price-${idx}" class="ruled-cell cell-num" placeholder="تلقائي أو يدوي" step="0.0001" min="0"
          value="${escapeHtml(row.unitPrice)}" oninput="onBatchRowUnitPriceInput(${idx}, this.value)">
      </div>
    </div>
  `).join('');
  batchEntryRows.forEach((row, idx) => updateBatchUnitOptions(idx));
  enforceEnglishNumericFields(container);
}

function updateBatchUnitOptions(idx) {
  const row = batchEntryRows[idx], select = document.getElementById('batch-unit-' + idx);
  if (!row || !select) return;
  const item = inventoryData.find(x => x.id === row.itemId) ||
    inventoryData.find(x => (x.name || '').trim().toLowerCase() === (row.itemName || '').trim().toLowerCase());
  const options = getInventoryUnitOptions(item);
  select.innerHTML = options.map(o => '<option value="' + escapeHtml(o.name) + '">' + escapeHtml(o.name) + '</option>').join('');
  const selected = options.find(o => o.name === row.unitName && o.factor === row.unitFactor) || options[0];
  row.unitName = selected.name; row.unitFactor = selected.factor; select.value = selected.name;
}

function onBatchRowUnitChange(idx, name) {
  const row = batchEntryRows[idx]; if (!row) return;
  const item = inventoryData.find(x => x.id === row.itemId) ||
    inventoryData.find(x => (x.name || '').trim().toLowerCase() === (row.itemName || '').trim().toLowerCase());
  const option = getInventoryUnitOptions(item).find(o => o.name === name); if (!option) return;
  row.unitName = option.name; row.unitFactor = option.factor;
  const basePrice = item ? ((document.getElementById('inv-type').value === 'PURCHASE') ? item.purchasePrice : item.sellingPrice) : 0;
  if (basePrice > 0) {
    row.unitPrice = String(Math.round(basePrice * option.factor * 10000) / 10000);
    row.lastEdited = 'PRICE';
    const qty = parseInt(row.qty, 10) || 1;
    row.total = String(Math.round(qty * Number(row.unitPrice) * 100) / 100);
    const p = document.getElementById('batch-price-' + idx), t = document.getElementById('batch-total-' + idx);
    if (p) p.value = row.unitPrice;
    if (t) t.value = row.total;
  }
}

function addBatchRow() {
  batchEntryRows.push(createBlankBatchRow());
  renderBatchEntryRows();
  const lastIdx = batchEntryRows.length - 1;
  setTimeout(() => {
    const el = document.getElementById(`batch-name-${lastIdx}`);
    if (el) el.focus();
  }, 40);
}

function removeBatchRow(idx) {
  if (batchEntryRows.length <= 1) {
    batchEntryRows = [createBlankBatchRow()];
  } else {
    batchEntryRows.splice(idx, 1);
  }
  renderBatchEntryRows();
}

function onBatchRowNameInput(idx, val) {
  const row = batchEntryRows[idx];
  if (!row) return;
  row.itemName = val;
  const clean = (val || '').trim().toLowerCase();
  const matched = clean ? inventoryData.find(x => (x.name || '').trim().toLowerCase() === clean) : null;
  const invType = document.getElementById('inv-type').value;
  const priceEl = document.getElementById(`batch-price-${idx}`);
  const totalEl = document.getElementById(`batch-total-${idx}`);
  if (matched) {
    row.itemId = matched.id;
    const options = getInventoryUnitOptions(matched);
    const selected = options.find(o => o.name === row.unitName && o.factor === row.unitFactor) || options[0];
    row.unitName = selected.name;
    row.unitFactor = selected.factor;
    updateBatchUnitOptions(idx);
    const suggestedPrice = (invType === 'PURCHASE' ? matched.purchasePrice : matched.sellingPrice) * row.unitFactor;
    if (suggestedPrice > 0) {
      row.unitPrice = String(Math.round(suggestedPrice * 10000) / 10000);
      row.lastEdited = 'PRICE';
      const qty = parseInt(row.qty, 10) || 1;
      row.total = String(Math.round(qty * suggestedPrice * 100) / 100);
      if (priceEl) priceEl.value = row.unitPrice;
      if (totalEl) totalEl.value = row.total;
    } else {
      row.unitPrice = '';
      row.total = '';
      if (priceEl) priceEl.value = '';
      if (totalEl) totalEl.value = '';
    }
  } else {
    row.itemId = 0;
    if (!row.unitName) { row.unitName = settingsData.defaultInventoryUnit || 'حبة'; row.unitFactor = 1; }
    updateBatchUnitOptions(idx);
  }
}

function onBatchRowTotalInput(idx, val) {
  const row = batchEntryRows[idx];
  if (!row) return;
  row.total = val;
  row.lastEdited = 'TOTAL';
  const qty = parseInt(row.qty, 10) || 1;
  const total = parseFloat(val);
  if (val === '') {
    row.unitPrice = '';
  } else if (qty > 0 && Number.isFinite(total) && total >= 0) {
    row.unitPrice = String(Math.round((total / qty) * 10000) / 10000);
  }
  const priceEl = document.getElementById(`batch-price-${idx}`);
  if (priceEl) priceEl.value = row.unitPrice;
}

function onBatchRowQtyInput(idx, val) {
  const row = batchEntryRows[idx];
  if (!row) return;
  row.qty = val;
  const qty = val === '' ? 1 : parseInt(val, 10);
  if (!Number.isFinite(qty) || qty <= 0) return;

  if (row.lastEdited === 'PRICE' && row.unitPrice !== '') {
    const price = parseFloat(row.unitPrice);
    if (Number.isFinite(price) && price >= 0) {
      row.total = String(Math.round(qty * price * 100) / 100);
      const totalEl = document.getElementById(`batch-total-${idx}`);
      if (totalEl) totalEl.value = row.total;
    }
  } else if (row.total !== '') {
    const total = parseFloat(row.total);
    if (Number.isFinite(total) && total >= 0) {
      row.unitPrice = String(Math.round((total / qty) * 10000) / 10000);
      const priceEl = document.getElementById(`batch-price-${idx}`);
      if (priceEl) priceEl.value = row.unitPrice;
    }
  } else if (row.unitPrice !== '') {
    const price = parseFloat(row.unitPrice);
    if (Number.isFinite(price) && price >= 0) {
      row.total = String(Math.round(qty * price * 100) / 100);
      const totalEl = document.getElementById(`batch-total-${idx}`);
      if (totalEl) totalEl.value = row.total;
    }
  }
}

function onBatchRowUnitPriceInput(idx, val) {
  const row = batchEntryRows[idx];
  if (!row) return;
  row.unitPrice = val;
  row.lastEdited = 'PRICE';
  const qty = parseInt(row.qty, 10) || 1;
  const price = parseFloat(val);
  if (val === '') {
    row.total = '';
  } else if (qty > 0 && Number.isFinite(price) && price >= 0) {
    row.total = String(Math.round(qty * price * 100) / 100);
  }
  const totalEl = document.getElementById(`batch-total-${idx}`);
  if (totalEl) totalEl.value = row.total;
}

function appendItemLineToCart(itemId, itemName, qty, unitPrice, totalPrice, unitName = '', unitFactor = 1) {
  const normalizedUnit = Math.round((totalPrice / qty) * 10000) / 10000;
  const cleanName = itemName.trim();
  const existingIdx = cartItems.findIndex(x =>
    x.itemId === itemId &&
    x.itemName.trim().toLowerCase() === cleanName.toLowerCase() &&
    (x.unitName || '') === unitName && (x.unitFactor || 1) === unitFactor &&
    Math.abs(x.unitPrice - normalizedUnit) < 0.0001
  );
  if (existingIdx >= 0) {
    cartItems[existingIdx].quantity += qty;
    cartItems[existingIdx].stockQuantity = (cartItems[existingIdx].stockQuantity || 0) + qty * unitFactor;
    cartItems[existingIdx].totalPrice = Math.round(cartItems[existingIdx].quantity * normalizedUnit * 100) / 100;
  } else {
    cartItems.push({
      itemId: itemId || 0,
      itemName: cleanName,
      quantity: qty,
      unitPrice: normalizedUnit,
      totalPrice: Math.round(totalPrice * 100) / 100,
      unitName, unitFactor, stockQuantity: qty * unitFactor
    });
  }
}

function addBatchItemsToCart() {
  const pendingLines = [];
  let errorMsg = '';

  for (let i = 0; i < batchEntryRows.length; i++) {
    const r = batchEntryRows[i];
    const name = (r.itemName || '').trim();
    const hasPriceOrTotal = r.total !== '' || r.unitPrice !== '';
    if (!name && !hasPriceOrTotal) continue;

    if (!name) {
      errorMsg = `أدخل اسم الصنف في الصف ${i + 1}`;
      break;
    }
    const qty = r.qty === '' ? 1 : parseInt(r.qty, 10);
    if (!Number.isFinite(qty) || qty <= 0) {
      errorMsg = `الكمية غير صحيحة في الصف ${i + 1}`;
      break;
    }
    const directTotal = parseFloat(r.total);
    let unitPrice = parseFloat(r.unitPrice);
    if ((!Number.isFinite(unitPrice) || unitPrice < 0) && Number.isFinite(directTotal) && directTotal >= 0) {
      unitPrice = directTotal / qty;
    }
    if (!Number.isFinite(unitPrice) || unitPrice < 0) {
      errorMsg = `أدخل الإجمالي أو السعر للصنف "${name}"`;
      break;
    }

    const totalPrice = (Number.isFinite(directTotal) && directTotal >= 0)
      ? Math.round(directTotal * 100) / 100
      : Math.round(qty * unitPrice * 100) / 100;

    // Resolve matching inventory item ID if user typed an inventory item name
    const matched = inventoryData.find(x => (x.name || '').trim().toLowerCase() === name.toLowerCase());
    const resolvedId = r.itemId > 0 ? r.itemId : (matched ? matched.id : 0);

    const unitFactor = r.unitFactor || 1;
    const unitName = (r.unitName || (matched ? parseInventoryUnitConfig(matched.unit).base : 'حبة')).trim();
    pendingLines.push({ itemId: resolvedId, itemName: name, qty, unitPrice, totalPrice, unitName, unitFactor });
  }

  if (errorMsg) {
    showToast(errorMsg, 'error');
    return;
  }
  if (pendingLines.length === 0) {
    showToast('أدخل صنفاً واحداً أو أكثر ثم اضغط إضافة', 'error');
    return;
  }

  pendingLines.forEach(line => {
    appendItemLineToCart(line.itemId, line.itemName, line.qty, line.unitPrice, line.totalPrice, line.unitName, line.unitFactor);
  });

  invoiceDirty = true;
  batchEntryRows = [createBlankBatchRow()];
  renderBatchEntryRows();
  syncCashPaidWithCart();
  renderCart();
  showToast(`تمت إضافة ${pendingLines.length} ${pendingLines.length === 1 ? 'صنف' : 'أصناف'} إلى الفاتورة ✓`, 'success');
}

function addCartItem() {
  addBatchItemsToCart();
}

function toggleMultiStockPicker(forceState) {
  const box = document.getElementById('multi-stock-picker-box');
  if (!box) return;
  const nextShow = typeof forceState === 'boolean' ? forceState : box.classList.contains('hidden');
  // Keep the fixed dialog as a direct body child so parent view stacking/overflow cannot clip it.
  if (nextShow && box.parentElement !== document.body) document.body.appendChild(box);
  box.classList.toggle('hidden', !nextShow);
  box.setAttribute('aria-hidden', nextShow ? 'false' : 'true');
  document.body.classList.toggle('stock-picker-open', nextShow);
  if (nextShow) {
    renderMultiStockPicker();
    const search = document.getElementById('multi-stock-search');
    if (search) setTimeout(() => search.focus(), 80);
  }
}

function toggleInvoiceCartFullscreen(forceState) {
  const panel = document.getElementById('invoice-cart-panel');
  const button = document.getElementById('invoice-cart-expand-btn');
  if (!panel) return;
  const expand = typeof forceState === 'boolean' ? forceState : !panel.classList.contains('is-fullscreen');
  panel.classList.toggle('is-fullscreen', expand);
  document.body.classList.toggle('invoice-cart-expanded', expand);
  if (button) {
    button.textContent = expand ? 'إغلاق التكبير ×' : 'ملء الشاشة ⛶';
    button.setAttribute('aria-expanded', expand ? 'true' : 'false');
  }
}

function toggleQuickAddParty(forceState) {
  const box = document.getElementById('quick-add-party-box');
  if (!box) return;
  const type = document.getElementById('inv-type').value;
  const show = typeof forceState === 'boolean' ? forceState : box.classList.contains('hidden');
  box.classList.toggle('hidden', !show);
  const title = document.getElementById('quick-party-title');
  const extra = document.getElementById('quick-party-extra');
  const name = document.getElementById('quick-party-name');
  if (title) title.textContent = type === 'PURCHASE' ? 'إضافة مورد جديد' : 'إضافة عميل جديد';
  if (extra) extra.placeholder = type === 'PURCHASE' ? 'اسم الشركة' : 'العنوان';
  if (show && name) setTimeout(() => name.focus(), 40);
}

function submitQuickAddParty() {
  const type = document.getElementById('inv-type').value;
  const name = (document.getElementById('quick-party-name').value || '').trim();
  const phone = (document.getElementById('quick-party-phone').value || '').trim();
  const extra = (document.getElementById('quick-party-extra').value || '').trim();
  if (!name) { showToast('أدخل الاسم أولاً', 'error'); return; }
  if (!window.Android) return;
  const isSupplier = type === 'PURCHASE';
  const payload = isSupplier
    ? { id: 0, name, company: extra, phone, balance: 0 }
    : { id: 0, name, phone, address: extra, balance: 0 };
  const reqId = nextReqId(created => {
    if (!created || !created.id) {
      showToast('تم الحفظ، حدّث قائمة الأطراف ثم اختر الاسم', 'success');
      if (isSupplier) window.Android.getSuppliers(); else window.Android.getCustomers();
      return;
    }
    toggleQuickAddParty(false);
    ['quick-party-name','quick-party-phone','quick-party-extra'].forEach(id => { const el = document.getElementById(id); if (el) el.value = ''; });
    pendingInvoicePartyId = Number(created.id) || 0;
    if (isSupplier) window.Android.getSuppliers(); else window.Android.getCustomers();
    updatePartyBalanceChip();
    markInvoiceDirty();
    showToast(isSupplier ? 'تمت إضافة المورد واختياره ✓' : 'تمت إضافة العميل واختياره ✓', 'success');
  });
  if (isSupplier) window.Android.saveSupplier(JSON.stringify(payload), reqId);
  else window.Android.saveCustomer(JSON.stringify(payload), reqId);
}

function renderMultiStockPicker() {
  const container = document.getElementById('multi-stock-list');
  if (!container) return;
  const searchEl = document.getElementById('multi-stock-search');
  const query = (searchEl ? searchEl.value : '').trim().toLowerCase();
  const type = document.getElementById('inv-type').value;
  const list = inventoryData.filter(i => !query || (i.name || '').toLowerCase().includes(query) || (i.category || '').toLowerCase().includes(query));

  const count = document.getElementById('stock-picker-count');
  if (count) count.textContent = `${list.length} صنف`;
  if (list.length === 0) {
    container.innerHTML = '<div class="muted-text" style="grid-column:1/-1;text-align:center;padding:24px 10px;">لا توجد أصناف مطابقة. يمكنك إضافة صنف جديد للمخزن.</div>';
    return;
  }
  const lowThreshold = Math.max(1, Number(settingsData.lowStockThreshold) || 10);
  container.innerHTML = list.map(i => {
    const defaultUnit = getInventoryUnitOptions(i)[0] || { name: parseInventoryUnitConfig(i.unit).base || 'حبة', factor: 1 };
    const basePrice = Number(type === 'PURCHASE' ? i.purchasePrice : i.sellingPrice) || 0;
    const price = Math.round(basePrice * defaultUnit.factor * 10000) / 10000;
    const qty = Number(i.quantity) || 0;
    const stockClass = qty <= 0 ? 'text-danger' : (qty <= lowThreshold ? 'text-primary' : 'text-success');
    const inCartQty = cartItems
      .filter(line => Number(line.itemId) === Number(i.id))
      .reduce((sum, line) => sum + (Number(line.quantity) || 0), 0);
    return `
      <article class="quick-stock-pick-row${inCartQty > 0 ? ' is-in-cart' : ''}" role="button" tabindex="0"
        aria-label="إضافة ${escapeHtml(i.name)} إلى الفاتورة"
        onclick="addInventoryItemQuick(${i.id})"
        onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();addInventoryItemQuick(${i.id})}">
        <div class="quick-stock-pick-top">
          <strong class="quick-stock-pick-name">${escapeHtml(i.name)}</strong>
          ${inCartQty > 0 ? `<span class="quick-stock-pick-badge">×${fmtMoney(inCartQty)}</span>` : ''}
        </div>
        <div class="quick-stock-pick-meta">
          <span class="quick-stock-pick-price">${fmtMoney(price)} ${escapeHtml(appCurrency)} / ${escapeHtml(defaultUnit.name)}</span>
          <span class="quick-stock-pick-qty ${stockClass}">${fmtMoney(qty)}</span>
        </div>
      </article>
    `;
  }).join('');
}

function addInventoryItemQuick(itemId) {
  const item = inventoryData.find(x => Number(x.id) === Number(itemId));
  if (!item) {
    showToast('تعذر العثور على الصنف؛ حدّث قائمة المخزن وحاول مجدداً', 'error');
    return;
  }
  const type = document.getElementById('inv-type').value;
  const basePrice = Math.max(0, Number(type === 'PURCHASE' ? item.purchasePrice : item.sellingPrice) || 0);
  const defaultUnit = getInventoryUnitOptions(item)[0] || { name: parseInventoryUnitConfig(item.unit).base || 'حبة', factor: 1 };
  const unitPrice = Math.round(basePrice * defaultUnit.factor * 10000) / 10000;
  appendItemLineToCart(item.id, item.name, 1, unitPrice, unitPrice, defaultUnit.name, defaultUnit.factor);
  invoiceDirty = true;
  syncCashPaidWithCart();
  renderCart();
  renderMultiStockPicker();
  showToast(`تمت إضافة وحدة من ${item.name} ✓`, 'success');
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
    document.getElementById('quick-stock-qty').value = '';
    document.getElementById('quick-stock-buy').value = '';
    document.getElementById('quick-stock-sell').value = '';
    setTimeout(() => {
      if (created && created.id && batchEntryRows.length > 0) {
        const idx = 0;
        const type = document.getElementById('inv-type').value;
        const suggested = type === 'PURCHASE' ? created.purchasePrice : created.sellingPrice;
        batchEntryRows[idx].itemId = created.id;
        batchEntryRows[idx].itemName = created.name;
        batchEntryRows[idx].unitName = parseInventoryUnitConfig(created.unit).base || 'حبة';
        batchEntryRows[idx].unitFactor = 1;
        batchEntryRows[idx].unitPrice = suggested > 0 ? String(suggested) : '';
        batchEntryRows[idx].total = suggested > 0 ? String(suggested) : '';
        renderBatchEntryRows();
      }
    }, 150);
  });
  window.Android.saveInventoryItem(JSON.stringify({
    id: 0,
    name,
    category: settingsData.defaultInventoryCategory || 'مواد غذائية',
    unit: settingsData.defaultInventoryUnit || 'حبة',
    quantity,
    purchasePrice,
    sellingPrice
  }), reqId);
}

function onCartQtyInput(idx, newQtyVal) {
  const qty = parseInt(newQtyVal, 10);
  if (!Number.isFinite(qty) || qty <= 0 || !cartItems[idx]) return;
  cartItems[idx].quantity = qty;
  cartItems[idx].stockQuantity = qty * (cartItems[idx].unitFactor || 1);
  cartItems[idx].totalPrice = Math.round(qty * cartItems[idx].unitPrice * 100) / 100;
  invoiceDirty = true;
  const totEl = document.getElementById(`cart-line-total-${idx}`);
  if (totEl) totEl.value = cartItems[idx].totalPrice;
  syncCashPaidWithCart();
  updateInvoiceTotals();
}

function onCartLineTotalInput(idx, newTotalVal) {
  const total = parseFloat(newTotalVal);
  if (!Number.isFinite(total) || total < 0 || !cartItems[idx]) return;
  const qty = cartItems[idx].quantity || 1;
  cartItems[idx].totalPrice = Math.round(total * 100) / 100;
  cartItems[idx].unitPrice = Math.round((total / qty) * 10000) / 10000;
  invoiceDirty = true;
  const priceEl = document.getElementById(`cart-line-price-${idx}`);
  if (priceEl) priceEl.value = cartItems[idx].unitPrice;
  syncCashPaidWithCart();
  updateInvoiceTotals();
}

function onCartUnitPriceInput(idx, newPriceVal) {
  const price = parseFloat(newPriceVal);
  if (!Number.isFinite(price) || price < 0 || !cartItems[idx]) return;
  const qty = cartItems[idx].quantity || 1;
  cartItems[idx].unitPrice = Math.round(price * 10000) / 10000;
  cartItems[idx].totalPrice = Math.round(qty * price * 100) / 100;
  invoiceDirty = true;
  const totEl = document.getElementById(`cart-line-total-${idx}`);
  if (totEl) totEl.value = cartItems[idx].totalPrice;
  syncCashPaidWithCart();
  updateInvoiceTotals();
}

function updateCartQty(idx, newQtyVal) {
  const qty = parseInt(newQtyVal, 10);
  if (!Number.isFinite(qty) || qty <= 0 || !cartItems[idx]) { renderCart(); return; }
  cartItems[idx].quantity = qty;
  cartItems[idx].stockQuantity = qty * (cartItems[idx].unitFactor || 1);
  cartItems[idx].totalPrice = Math.round(qty * cartItems[idx].unitPrice * 100) / 100;
  invoiceDirty = true;
  syncCashPaidWithCart();
  renderCart();
}

function updateCartLineTotal(idx, newTotalVal) {
  const total = parseFloat(newTotalVal);
  if (!Number.isFinite(total) || total < 0 || !cartItems[idx]) { renderCart(); return; }
  const qty = cartItems[idx].quantity || 1;
  cartItems[idx].totalPrice = Math.round(total * 100) / 100;
  cartItems[idx].unitPrice = Math.round((total / qty) * 10000) / 10000;
  invoiceDirty = true;
  syncCashPaidWithCart();
  renderCart();
}

function updateCartUnitPrice(idx, newPriceVal) {
  const price = parseFloat(newPriceVal);
  if (!Number.isFinite(price) || price < 0 || !cartItems[idx]) { renderCart(); return; }
  const qty = cartItems[idx].quantity || 1;
  cartItems[idx].unitPrice = Math.round(price * 10000) / 10000;
  cartItems[idx].totalPrice = Math.round(qty * price * 100) / 100;
  invoiceDirty = true;
  syncCashPaidWithCart();
  renderCart();
}

function updateCartItemName(idx, newNameVal) {
  const clean = (newNameVal || '').trim();
  if (!clean) { renderCart(); return; }
  const line = cartItems[idx];
  if (!line) return;
  const matched = inventoryData.find(x => (x.name || '').trim().toLowerCase() === clean.toLowerCase());
  line.itemName = clean;
  line.itemId = matched ? matched.id : 0;
  if (matched) {
    const options = getInventoryUnitOptions(matched);
    const selected = options.find(o => o.name === line.unitName && o.factor === line.unitFactor) || options[0];
    line.unitName = selected.name;
    line.unitFactor = selected.factor;
  } else {
    line.unitName = settingsData.defaultInventoryUnit || 'حبة';
    line.unitFactor = 1;
  }
  line.stockQuantity = line.quantity * (line.unitFactor || 1);
  invoiceDirty = true;
  syncCashPaidWithCart();
  renderCart();
}

function removeCartItem(index) {
  cartItems.splice(index, 1);
  invoiceDirty = true;
  syncCashPaidWithCart();
  renderCart();
}

function clearCart() {
  cartItems = [];
  invoiceDirty = true;
  document.getElementById('inv-paid').value = '';
  renderCart();
}

function renderCart() {
  const tbody = document.getElementById('cart-items-tbody');
  document.getElementById('inv-items-count-badge').textContent = `${cartItems.length} أصناف`;
  if (cartItems.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--text-secondary);">أضف صنفاً أو عدة أصناف من الجدول المسطر بالأعلى</td></tr>';
  } else {
    tbody.innerHTML = cartItems.map((item, idx) => `
      <tr>
        <td><input type="number" id="cart-line-total-${idx}" class="qty-inline-input" style="color:var(--success-green);" min="0" step="0.01" value="${item.totalPrice}" oninput="onCartLineTotalInput(${idx}, this.value)" onchange="updateCartLineTotal(${idx}, this.value)"></td>
        <td>
          <input type="number" lang="en" dir="ltr" inputmode="numeric" id="cart-line-qty-${idx}" class="qty-inline-input cart-qty-direct" min="1" step="1" value="${item.quantity}" oninput="onCartQtyInput(${idx}, this.value)" onchange="updateCartQty(${idx}, this.value)">
          ${item.unitName ? `<small class="cart-unit-label">${escapeHtml(item.unitName)}</small>` : ''}
        </td>
        <td><input type="text" id="cart-line-name-${idx}" class="inline-name-input" value="${escapeHtml(item.itemName)}" onchange="updateCartItemName(${idx}, this.value)"></td>
        <td><input type="number" id="cart-line-price-${idx}" class="qty-inline-input" style="color:var(--dark-header); background:#F5F8FF;" min="0" step="0.0001" value="${item.unitPrice}" oninput="onCartUnitPriceInput(${idx}, this.value)" onchange="updateCartUnitPrice(${idx}, this.value)"></td>
        <td><button type="button" class="tbl-action-btn del" onclick="removeCartItem(${idx})">حذف</button></td>
      </tr>
    `).join('');
  }
  enforceEnglishNumericFields(tbody);
  updateInvoiceTotals();
}

function toggleInvoiceWaMenu(forceState) {
  const menu = document.getElementById('invoice-wa-menu');
  if (!menu) return;
  const nextShow = typeof forceState === 'boolean' ? forceState : menu.classList.contains('hidden');
  menu.classList.toggle('hidden', !nextShow);
}

function setPaymentMode(mode) {
  if (document.getElementById('inv-type').value !== 'PURCHASE') {
    document.getElementById('inv-type').value = mode;
  }
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  document.getElementById('inv-paid').value = mode === 'CASH' && settingsData.autoFillCashPaid !== false && subtotal > 0
    ? String(Math.round(subtotal * 100) / 100)
    : '';
  invoiceDirty = true;
  updatePaymentModeButtons();
  updateInvoiceTotals();
}

function updatePaymentModeButtons() {
  const type = document.getElementById('inv-type').value;
  if (type === 'CASH' && settingsData.autoFillCashPaid !== false) {
    const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
    const paidEl = document.getElementById('inv-paid');
    if (paidEl && subtotal > 0 && (!paidEl.value || parseFloat(paidEl.value) === 0)) {
      paidEl.value = String(Math.round(subtotal * 100) / 100);
    }
  }
}

function payFullInvoice() {
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  document.getElementById('inv-paid').value = subtotal > 0 ? String(Math.round(subtotal * 100) / 100) : '';
  invoiceDirty = true;
  updateInvoiceTotals();
}

function updateInvoiceTotals() {
  const subtotal = cartItems.reduce((acc, cur) => acc + cur.totalPrice, 0);
  const rawPaid = document.getElementById('inv-paid').value;
  const paid = rawPaid === '' ? 0 : (parseFloat(rawPaid) || 0);
  const remaining = Math.max(0, subtotal - paid);
  const isPurchase = document.getElementById('inv-type').value === 'PURCHASE';
  document.getElementById('inv-total-display').textContent = `إجمالي فاتورة ${isPurchase ? 'الشراء' : 'البيع'}: ${fmtMoney(subtotal)} ${appCurrency}`;
  document.getElementById('inv-remaining-display').textContent = `${isPurchase ? 'المتبقي للمورد' : 'المتبقي على العميل'}: ${fmtMoney(remaining)} ${appCurrency}`;

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

function resolveSelectedPartyName(type, partyId) {
  if (partyId > 0) {
    if (type === 'PURCHASE') {
      const s = suppliersData.find(x => Number(x.id) === partyId);
      if (s && s.name) return s.name;
    } else {
      const c = customersData.find(x => Number(x.id) === partyId);
      if (c && c.name) return c.name;
    }
  }
  const partySelect = document.getElementById('inv-party');
  const selectedOpt = partySelect && partySelect.options ? partySelect.options[partySelect.selectedIndex] : null;
  if (selectedOpt && selectedOpt.dataset && selectedOpt.dataset.partyName) {
    return selectedOpt.dataset.partyName.trim() || (type === 'PURCHASE' ? '' : 'عميل نقدي');
  }
  return type === 'PURCHASE' ? '' : 'عميل نقدي';
}

function saveInvoiceAction(followUpAction) {
  if (!window.Android || isSavingInvoice) return;

  if (editingInvoiceId > 0 && !invoiceDirty && followUpAction !== 'NONE') {
    runInvoiceFollowUp(editingInvoiceId, followUpAction);
    return;
  }

  const number = (document.getElementById('inv-number').value || '').trim();
  const type = document.getElementById('inv-type').value;
  const partyId = parseInt(document.getElementById('inv-party').value, 10) || 0;
  const partyName = resolveSelectedPartyName(type, partyId);
  const rawPaid = document.getElementById('inv-paid').value;
  const paid = rawPaid === '' ? 0 : (parseFloat(rawPaid) || 0);
  const invDateEl = document.getElementById('inv-date');
  const dateMillis = invDateEl ? fromDateTimeLocalValue(invDateEl.value) : 0;

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
    items: cartItems.map(item => ({
      ...item,
      unitLabel: item.unitName || item.unitLabel || '',
      unitFactor: item.unitFactor || 1,
      stockQuantity: item.quantity * (item.unitFactor || 1)
    })),
    paidAmount: paid,
    date: dateMillis
  };

  setInvoiceSubmitBusy(true);
  const reqId = nextReqId(
    (data) => {
      setInvoiceSubmitBusy(false);
      const savedId = (data && data.invoiceId) ? data.invoiceId : editingInvoiceId;
      editingInvoiceId = savedId;
      invoiceDirty = false;
      document.getElementById('invoice-saved-badge').classList.remove('hidden');
      updateInvoiceModeUI();
      if (followUpAction && followUpAction !== 'NONE' && savedId > 0) {
        runInvoiceFollowUp(savedId, followUpAction);
      } else {
        // A normal successful save returns to the invoice register; failures stay on this form.
        editingInvoiceId = 0;
        invoiceDirty = false;
        switchView('invoices', false);
      }
    },
    () => {
      setInvoiceSubmitBusy(false);
    }
  );
  window.Android.saveInvoice(JSON.stringify(payload), reqId);
}

function runInvoiceFollowUp(invoiceId, action) {
  if (!window.Android || !invoiceId) return;
  if (action === 'THERMAL_PREVIEW') {
    thermalReturnView = 'create-invoice';
    activePreviewInvoiceId = invoiceId;
    verifiedPreviewSignature = '';
    const btn = document.getElementById('btn-thermal-print-now');
    if (btn) btn.disabled = true;
    window.Android.previewThermalInvoice(invoiceId, nextReqId());
    return;
  }

  // Close the editor only after the PDF/share action itself succeeds.
  const closeAfterSuccess = nextReqId(() => {
    editingInvoiceId = 0;
    invoiceDirty = false;
    switchView('invoices', false);
  });
  if (action === 'PDF') {
    window.Android.generateAndOpenPdf(invoiceId, closeAfterSuccess);
  } else if (action === 'WA_IMAGE') {
    window.Android.shareInvoiceImage(invoiceId, closeAfterSuccess);
  } else if (action === 'WA_PDF') {
    window.Android.shareInvoicePdf(invoiceId, closeAfterSuccess);
  }
}

window.onThermalPreviewData = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const kind = (data.kind || 'INVOICE').toUpperCase();
    activePreviewState = {
      kind,
      targetId: kind === 'INVOICE' ? (data.invoiceId || 0) : (data.targetId || 0),
      extraStr: data.extraStr || '',
      startMillis: data.startMillis || 0,
      endMillis: data.endMillis || 0
    };
    activePreviewInvoiceId = kind === 'INVOICE' ? (data.invoiceId || 0) : 0;
    verifiedPreviewSignature = data.previewSignature || '';

    document.getElementById('thermal-preview-title').textContent =
      data.title || (data.invoiceNumber ? `معاينة فاتورة #${data.invoiceNumber}` : 'معاينة الطباعة الحرارية');
    document.getElementById('thermal-width-badge').textContent = `${data.widthMm} مم`;
    document.getElementById('thermal-printer-status-bar').textContent = data.connected
      ? `متصل بالطابعة: ${data.printerName || 'طابعة بلوتوث'}`
      : 'تنبيه: الطابعة الحرارية غير متصلة حالياً — يمكنك الاتصال بها من الإعدادات';
    document.getElementById('thermal-preview-img').src = 'data:image/png;base64,' + data.imageBase64;
    const p58 = document.getElementById('preview-width-58');
    const p80 = document.getElementById('preview-width-80');
    if (p58) p58.className = data.widthMm === 58 ? 'btn btn-primary btn-compact' : 'btn btn-outline btn-compact';
    if (p80) p80.className = data.widthMm === 80 ? 'btn btn-primary btn-compact' : 'btn btn-outline btn-compact';

    const printBtn = document.getElementById('btn-thermal-print-now');
    if (printBtn) printBtn.disabled = false;

    const waBtn = document.getElementById('btn-preview-wa-share');
    const pdfBtn = document.getElementById('btn-preview-open-pdf');
    if (waBtn) waBtn.classList.toggle('hidden', kind !== 'INVOICE' && kind !== 'STATEMENT' && kind !== 'REPORT' && kind !== 'NOTE');
    if (pdfBtn) pdfBtn.classList.toggle('hidden', kind === 'TX');

    switchView('thermal-preview');
  } catch (e) {
    console.error(e);
  }
};

function requestActivePreviewRefresh() {
  if (!window.Android) return;
  const st = activePreviewState;
  if (st.kind === 'INVOICE' && st.targetId) {
    window.Android.previewThermalInvoice(st.targetId, nextReqId());
  } else if (st.kind && st.kind !== 'INVOICE') {
    window.Android.previewGenericThermal(st.kind, st.targetId, st.extraStr, st.startMillis, st.endMillis, nextReqId());
  }
}

function switchPreviewWidth(widthMm) {
  if (!window.Android) return;
  verifiedPreviewSignature = '';
  const printBtn = document.getElementById('btn-thermal-print-now');
  if (printBtn) printBtn.disabled = true;
  const reqId = nextReqId(() => {
    requestActivePreviewRefresh();
  });
  window.Android.setPrinterWidth(widthMm, reqId);
}

function printActivePreviewInvoice() {
  if (!window.Android) return;
  if (!verifiedPreviewSignature) {
    showToast('المعاينة إلزامية قبل الطباعة — جاري تحديث المعاينة', 'error');
    requestActivePreviewRefresh();
    return;
  }
  const st = activePreviewState;
  if (st.kind === 'INVOICE' && st.targetId) {
    window.Android.printInvoice(st.targetId, nextReqId());
  } else {
    window.Android.printGenericThermal(st.kind, st.targetId, st.extraStr, st.startMillis, st.endMillis, nextReqId());
  }
}

function shareActivePreviewImage() {
  if (!window.Android) return;
  const st = activePreviewState;
  if (st.kind === 'INVOICE' && st.targetId) {
    window.Android.shareInvoiceImage(st.targetId, nextReqId());
  } else if (st.kind === 'STATEMENT' && st.targetId) {
    window.Android.shareStatementWhatsAppPdf(st.extraStr, st.targetId, nextReqId());
  } else if (st.kind === 'REPORT') {
    window.Android.shareReportWhatsAppPdf(st.startMillis, st.endMillis, st.extraStr, nextReqId());
  } else if (st.kind === 'NOTE' && st.targetId) {
    window.Android.shareNoteThermalImageWhatsApp(st.targetId, nextReqId());
  }
}

function openActivePreviewPdf() {
  if (!window.Android) return;
  const st = activePreviewState;
  if (st.kind === 'INVOICE' && st.targetId) {
    window.Android.generateAndOpenPdf(st.targetId, nextReqId());
  } else if (st.kind === 'STATEMENT' && st.targetId) {
    window.Android.exportStatementPdf(st.extraStr, st.targetId, nextReqId());
  } else if (st.kind === 'REPORT') {
    window.Android.exportFinancialReportPdf(st.startMillis, st.endMillis, st.extraStr, nextReqId());
  } else if (st.kind === 'NOTE' && st.targetId) {
    window.Android.exportNotePdf(st.targetId, nextReqId());
  }
}

function saveActiveThermalImageToFolder() {
  if (!window.Android) return;
  const st = activePreviewState;
  if (st.kind === 'INVOICE' && st.targetId) {
    window.Android.saveThermalPreviewToAppFolder(st.targetId, nextReqId());
  } else {
    window.Android.saveGenericThermalToAppFolder(st.kind, st.targetId, st.extraStr, st.startMillis, st.endMillis, nextReqId());
  }
}

function returnFromThermalPreview() {
  const dest = thermalReturnView || 'create-invoice';
  if (viewHistory.length && viewHistory[viewHistory.length - 1] === dest) viewHistory.pop();
  if (dest === 'customer-detail' && activeCustomerId) {
    switchView(dest, false);
    window.Android && window.Android.getCustomerDetail(activeCustomerId);
  } else if (dest === 'supplier-detail' && activeSupplierId) {
    switchView(dest, false);
    window.Android && window.Android.getSupplierDetail(activeSupplierId);
  } else {
    switchView(dest, false);
  }
}


// ---------------- Two-panel Notes ----------------
function noteRowsFor(side) { return side === 'right' ? noteRightRows : noteLeftRows; }
const NOTE_CURSOR_STORAGE_KEY = 'alaziy-note-editor-cursor-v1';
function saveNoteEditorCursor(side, index, field, input) {
  const target = input || document.activeElement;
  if (!target || !target.classList?.contains('note-cell')) return;
  let start = target.value?.length || 0, end = start;
  try { start = target.selectionStart ?? start; end = target.selectionEnd ?? start; } catch (_) {}
  const scroll = document.getElementById('main-scroll');
  const snapshot = {
    noteId: Number(noteEditorId) || 0, side, index: Number(index) || 0, field,
    start, end, scrollTop: scroll ? scroll.scrollTop : 0
  };
  try { localStorage.setItem(NOTE_CURSOR_STORAGE_KEY, JSON.stringify(snapshot)); } catch (_) {}
}
function restoreNoteEditorCursor(noteId) {
  let snapshot = null;
  try { snapshot = JSON.parse(localStorage.getItem(NOTE_CURSOR_STORAGE_KEY) || 'null'); } catch (_) {}
  if (!snapshot || Number(snapshot.noteId) !== Number(noteId) ||
      !['right', 'left'].includes(snapshot.side) || !['qty', 'detail'].includes(snapshot.field)) return;
  const scroll = document.getElementById('main-scroll');
  if (scroll && Number.isFinite(Number(snapshot.scrollTop))) scroll.scrollTop = Math.max(0, Number(snapshot.scrollTop));
  requestAnimationFrame(() => {
    const body = document.getElementById(snapshot.side === 'right' ? 'note-right-rows' : 'note-left-rows');
    const input = body?.children?.[Number(snapshot.index)]?.querySelector('.note-' + snapshot.field);
    if (!input) return;
    input.focus({ preventScroll: true });
    const length = input.value.length;
    const start = Math.max(0, Math.min(length, Number(snapshot.start) || 0));
    const end = Math.max(start, Math.min(length, Number(snapshot.end) || start));
    try { input.setSelectionRange(start, end); } catch (_) {}
    if (scroll && Number.isFinite(Number(snapshot.scrollTop))) scroll.scrollTop = Math.max(0, Number(snapshot.scrollTop));
  });
}
function persistActiveNoteCursor(event) {
  const input = event?.target;
  if (!input?.classList?.contains('note-cell')) return;
  const body = input.closest('tbody[id^="note-"][id$="-rows"]');
  if (!body) return;
  const side = body.id === 'note-right-rows' ? 'right' : body.id === 'note-left-rows' ? 'left' : '';
  const row = input.closest('tr');
  const index = row ? Array.prototype.indexOf.call(body.children, row) : -1;
  if (side && index >= 0) saveNoteEditorCursor(side, index, input.classList.contains('note-qty') ? 'qty' : 'detail', input);
}
document.addEventListener('keyup', persistActiveNoteCursor, true);
document.addEventListener('pointerup', persistActiveNoteCursor, true);
document.addEventListener('focusin', persistActiveNoteCursor, true);

function noteRowMarkup(side, index) {
  const row = noteRowsFor(side)[index] || { qty: '', detail: '' };
  return `<tr>
    <td><input class="note-cell note-qty" type="text" inputmode="decimal" enterkeyhint="next" dir="ltr" aria-label="الكمية"
      value="${escapeHtml(row.qty)}" oninput="updateNoteCell('${side}',${index},'qty',this.value)"
      onkeydown="handleNoteCellKeydown(event,'${side}',${index},'qty')"></td>
    <td><input class="note-cell note-detail" type="text" enterkeyhint="next" aria-label="التفاصيل" placeholder="اسم الصنف أو التفاصيل"
      value="${escapeHtml(row.detail)}" oninput="updateNoteCell('${side}',${index},'detail',this.value)"
      onkeydown="handleNoteCellKeydown(event,'${side}',${index},'detail')"></td>
    <td class="note-row-action"><button type="button" class="row-remove-cell" tabindex="-1" aria-label="حذف الصف" title="حذف الصف" onclick="removeNoteRow('${side}',${index})">×</button></td>
  </tr>`;
}
function focusNoteCell(side, index, field) {
  const body = document.getElementById(side === 'right' ? 'note-right-rows' : 'note-left-rows');
  const row = body?.children?.[index];
  const input = row?.querySelector(`.note-${field}`);
  if (!input) return false;
  input.focus({ preventScroll: true });
  try { input.setSelectionRange(input.value.length, input.value.length); } catch (_) {}
  saveNoteEditorCursor(side, index, field, input);
  input.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  return document.activeElement === input;
}
function ensureNoteRow(side, index) {
  const rows = noteRowsFor(side);
  if (!rows[index]) {
    while (rows.length <= index) rows.push({ qty: '', detail: '' });
    const body = document.getElementById(side === 'right' ? 'note-right-rows' : 'note-left-rows');
    if (body) body.insertAdjacentHTML('beforeend', rows.slice(body.children.length).map((_, offset) => noteRowMarkup(side, body.children.length + offset)).join(''));
  }
}
function handleNoteCellKeydown(event, side, index, field) {
  const key = event.key;
  const code = event.keyCode || event.which;
  const isEnter = key === 'Enter' || key === 'NumpadEnter' || code === 13;
  const isTab = key === 'Tab' || code === 9;
  const isDown = key === 'ArrowDown' || code === 40;
  const isUp = key === 'ArrowUp' || code === 38;
  if ((!isEnter && !isTab && !isDown && !isUp) || event.isComposing) return;

  // Keep navigation inside the active notes panel; never tab into row-delete buttons
  // or unexpectedly jump from the second panel into another screen control.
  event.preventDefault();
  event.stopPropagation();
  const rows = noteRowsFor(side);
  let targetIndex = index;
  let targetField = field;

  if (isDown) targetIndex = index + 1;
  else if (isUp) targetIndex = Math.max(0, index - 1);
  else if (isEnter || (isTab && !event.shiftKey)) {
    if (field === 'qty') targetField = 'detail';
    else { targetIndex = index + 1; targetField = 'qty'; }
  } else if (isTab && event.shiftKey) {
    if (field === 'detail') targetField = 'qty';
    else { targetIndex = Math.max(0, index - 1); targetField = 'detail'; }
  }

  if (targetIndex >= rows.length && (isEnter || isTab || isDown)) {
    ensureNoteRow(side, targetIndex);
  }
  requestAnimationFrame(() => {
    if (!focusNoteCell(side, targetIndex, targetField)) {
      // Rebuild the panel only as a last resort if the IME changed the DOM mid-action.
      renderNoteRows(side);
      requestAnimationFrame(() => focusNoteCell(side, targetIndex, targetField));
    }
  });
}
function handleNoteImeAction() {
  const active = document.activeElement;
  if (!active?.classList?.contains('note-cell')) return false;
  const body = active.closest('tbody');
  if (!body || (body.id !== 'note-right-rows' && body.id !== 'note-left-rows')) return false;
  const side = body.id === 'note-right-rows' ? 'right' : 'left';
  const row = active.closest('tr');
  const index = row ? Array.prototype.indexOf.call(body.children, row) : -1;
  const field = active.classList.contains('note-qty') ? 'qty' : 'detail';
  if (index < 0) return false;
  handleNoteCellKeydown({
    key: 'Enter',
    keyCode: 13,
    which: 13,
    isComposing: false,
    shiftKey: false,
    preventDefault() {},
    stopPropagation() {}
  }, side, index, field);
  return true;
}
function renderNoteRows(side) {
  const body = document.getElementById(side === 'right' ? 'note-right-rows' : 'note-left-rows');
  if (!body) return;
  const rows = noteRowsFor(side);
  if (!rows.length) rows.push({ qty: '', detail: '' });
  body.innerHTML = rows.map((_, index) => noteRowMarkup(side, index)).join('');
}
let notePreviewVisible = true;
let notePreviewTimer = null;

function scheduleNoteThermalPreviewRefresh(delayMs = 220) {
  if (!notePreviewVisible) return;
  clearTimeout(notePreviewTimer);
  notePreviewTimer = setTimeout(() => {
    refreshNoteThermalPreview();
  }, delayMs);
}

function renderNoteEditor() {
  renderNoteRows('right'); renderNoteRows('left');
  refreshContextSuggestions();
  const status = document.getElementById('note-editor-status');
  if (status) status.textContent = noteEditorId ? 'ملاحظة محفوظة • الحفظ التلقائي نشط' : 'الحفظ التلقائي نشط — ابدأ الكتابة';
  const toggleBtn = document.getElementById('btn-note-preview-toggle');
  if (toggleBtn) toggleBtn.textContent = notePreviewVisible ? 'إخفاء المعاينة' : 'معاينة حرارية';
  scheduleNoteThermalPreviewRefresh(60);
}
function updateNoteCell(side, index, field, value) {
  const rows = noteRowsFor(side);
  if (!rows[index]) return;
  rows[index][field] = value;
  saveNoteEditorCursor(side, index, field, document.activeElement);
  noteEditorDirty = true;
  noteEditRevision++;
  queueNoteAutosave();
  if (index === rows.length - 1 && rows[index].qty.trim() && rows[index].detail.trim()) {
    rows.push({ qty: '', detail: '' });
    const body = document.getElementById(side === 'right' ? 'note-right-rows' : 'note-left-rows');
    if (body) body.insertAdjacentHTML('beforeend', noteRowMarkup(side, rows.length - 1));
  }
  scheduleNoteThermalPreviewRefresh(260);
}
function removeNoteRow(side, index) {
  const rows = noteRowsFor(side);
  if (!rows[index]) return;
  const row = rows[index];
  if ((String(row.qty || '').trim() || String(row.detail || '').trim()) &&
      !window.confirm('هل تريد حذف هذا الصف من الملاحظة؟ احفظ الملاحظة لتثبيت التغيير.')) return;
  rows.splice(index, 1);
  if (!rows.length) rows.push({ qty: '', detail: '' });
  noteEditorDirty = true;
  noteEditRevision++;
  queueNoteAutosave();
  renderNoteRows(side);
  const status = document.getElementById('note-editor-status');
  if (status) status.textContent = noteEditorId ? 'تعديل ملاحظة محفوظة — توجد تغييرات غير محفوظة' : 'ملاحظة جديدة غير محفوظة';
  scheduleNoteThermalPreviewRefresh(80);
}
function startNewNote() {
  if (noteEditorDirty) { clearTimeout(noteAutosaveTimer); performNoteSave(); }
  noteEditorSession++;
  noteEditorId = 0;
  try { localStorage.setItem('alaziy-last-note-id', '0'); } catch (_) {}
  noteEditorDirty = false;
  noteRightRows = [{ qty: '', detail: '' }];
  noteLeftRows = [{ qty: '', detail: '' }];
  renderNoteEditor();
  const scroll = document.getElementById('main-scroll'); if (scroll) scroll.scrollTop = 0;
}
function notePayloadRows(rows) {
  return rows.filter(r => (r.qty || '').trim() || (r.detail || '').trim())
    .map(r => ({ qty: (r.qty || '').trim(), detail: (r.detail || '').trim() }));
}
function noteHasContent(rightRows = notePayloadRows(noteRightRows), leftRows = notePayloadRows(noteLeftRows)) {
  return rightRows.length > 0 || leftRows.length > 0;
}
function queueNoteAutosave(delayMs = 750) {
  clearTimeout(noteAutosaveTimer);
  clearTimeout(noteAutosaveRetryTimer);
  noteAutosaveRetryCount = 0;
  const status = document.getElementById('note-editor-status');
  if (status) status.textContent = 'جارٍ الحفظ التلقائي…';
  noteAutosaveTimer = setTimeout(() => performNoteSave(false), delayMs);
}
function performNoteSave(manual = false) {
  if (!window.Android || !window.Android.saveNote) return;
  clearTimeout(noteAutosaveTimer);
  const rightRows = notePayloadRows(noteRightRows), leftRows = notePayloadRows(noteLeftRows);
  if (!noteHasContent(rightRows, leftRows) && !noteEditorId) return;
  if (noteAutosaveInFlight) {
    noteAutosavePending = true;
    noteManualSavePending = noteManualSavePending || manual;
    return;
  }
  noteAutosaveInFlight = true;
  noteAutosavePending = false;
  const payload = { id: noteEditorId, rightRows, leftRows, revision: noteEditRevision, session: noteEditorSession, manual };
  window.Android.saveNote(JSON.stringify(payload), nextReqId());
}
function saveCurrentNote() {
  if (!window.Android) return;
  if (!noteHasContent() && !noteEditorId) { showToast('أدخل كمية أو تفاصيل قبل الحفظ'); return; }
  performNoteSave(true);
}
window.onNoteSaved = function (jsonStr) {
  try {
    const result = JSON.parse(jsonStr) || {};
    noteAutosaveInFlight = false;
    if (Number(result.session) === noteEditorSession) {
      const previousNoteId = noteEditorId;
      noteEditorId = Number(result.id) || noteEditorId;
      try {
        localStorage.setItem('alaziy-last-note-id', String(noteEditorId));
        const cursor = JSON.parse(localStorage.getItem(NOTE_CURSOR_STORAGE_KEY) || 'null');
        if (cursor && Number(cursor.noteId) === Number(previousNoteId)) {
          cursor.noteId = Number(noteEditorId) || 0;
          localStorage.setItem(NOTE_CURSOR_STORAGE_KEY, JSON.stringify(cursor));
        }
      } catch (_) {}
      if (Number(result.revision) === noteEditRevision) {
        noteEditorDirty = false;
        noteAutosaveRetryCount = 0;
        clearTimeout(noteAutosaveRetryTimer);
        const status = document.getElementById('note-editor-status');
        if (status) status.textContent = result.manual ? 'تم حفظ الملاحظة ✓' : 'تم الحفظ تلقائياً ✓';
      } else {
        noteEditorDirty = true;
      }
    }
    refreshContextSuggestions();
    if (noteAutosavePending || noteEditorDirty) {
      noteAutosavePending = false;
      if (noteManualSavePending) {
        noteManualSavePending = false;
        performNoteSave(true);
      } else {
        queueNoteAutosave(180);
      }
    }
    if (refreshNotesHistoryAfterSave && currentView === 'notes-history' &&
        !noteAutosaveInFlight && !noteEditorDirty && !noteAutosavePending) {
      refreshNotesHistoryAfterSave = false;
      if (window.Android && window.Android.getNotes) window.Android.getNotes();
    }
  } catch (e) { noteAutosaveInFlight = false; console.error(e); }
};
window.onNoteSaveFailed = function (jsonStr) {
  noteAutosaveInFlight = false;
  try {
    const result = JSON.parse(jsonStr) || {};
    // A transient database/bridge failure must not leave a dirty note stranded.
    // Retry only a few times; further edits start a fresh retry cycle.
    const canRetry = !result.manual && noteEditorDirty && noteAutosaveRetryCount < 3;
    if (Number(result.session) === noteEditorSession) {
      const status = document.getElementById('note-editor-status');
      if (status) status.textContent = canRetry
        ? 'تعذر الحفظ — ستتم إعادة المحاولة تلقائياً'
        : 'لم يتم حفظ التغييرات — اضغط حفظ الملاحظة لإعادة المحاولة';
    }
    if (canRetry) {
      const delay = 1000 * Math.pow(2, noteAutosaveRetryCount++);
      clearTimeout(noteAutosaveRetryTimer);
      noteAutosaveRetryTimer = setTimeout(() => {
        if (noteEditorDirty && !noteAutosaveInFlight) performNoteSave(false);
      }, delay);
    } else if (refreshNotesHistoryAfterSave && currentView === 'notes-history') {
      refreshNotesHistoryAfterSave = false;
      if (window.Android && window.Android.getNotes) window.Android.getNotes();
      showToast('تعذر حفظ آخر التعديلات؛ يعرض السجل آخر نسخة محفوظة', 'error');
    }
  } catch (_) {}
};
window.flushPendingNoteAutosave = function () {
  if (noteEditorDirty) {
    clearTimeout(noteAutosaveTimer);
    performNoteSave(false);
  }
};
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') window.flushPendingNoteAutosave();
});
window.addEventListener('pagehide', () => window.flushPendingNoteAutosave());
window.onNotesData = function (jsonStr) {
  try {
    savedNotes = JSON.parse(jsonStr) || [];
    refreshContextSuggestions();
    const count = document.getElementById('notes-count');
    if (count) count.textContent = String(savedNotes.length);
    const list = document.getElementById('notes-history-list');
    if (!list) return;
    if (!savedNotes.length) {
      list.innerHTML = '<div class="empty-state">لا توجد ملاحظات محفوظة بعد</div>';
      restoreLastNoteOnLoad = false;
      return;
    }
    list.innerHTML = savedNotes.map(note => {
      const right = note.rightRows || [], left = note.leftRows || [], total = right.length + left.length;
      const first = [...right, ...left].find(r => r.detail || r.qty);
      return `<div class="note-history-item">
        <button type="button" class="note-history-open" onclick="openSavedNote(${Number(note.id)})">
          <strong>ملاحظة #${Number(note.id)}</strong>
          <span class="muted-text">فتح / تعديل</span>
          <span>${escapeHtml(fmtShortDate(note.updatedAt))} • ${total} صف</span>
          <small>${escapeHtml(first ? [first.qty, first.detail].filter(Boolean).join(' - ') : 'ملاحظة فارغة')}</small>
        </button>
        <button type="button" class="tbl-action-btn del" onclick="removeSavedNote(${Number(note.id)})">حذف</button>
      </div>`;
    }).join('');
    if (restoreLastNoteOnLoad) {
      restoreLastNoteOnLoad = false;
      let lastId = 0;
      try { lastId = Number(localStorage.getItem('alaziy-last-note-id')) || 0; } catch (_) {}
      if (lastId) openSavedNote(lastId);
    }
  } catch (e) { console.error(e); }
};
function openSavedNote(id) {
  const note = savedNotes.find(n => Number(n.id) === Number(id)); if (!note) return;
  if (currentView !== 'notes') switchView('notes');
  if (noteEditorDirty) { clearTimeout(noteAutosaveTimer); performNoteSave(); }
  noteEditorSession++;
  noteEditorId = Number(note.id);
  noteEditorDirty = false;
  noteEditRevision = 0;
  try { localStorage.setItem('alaziy-last-note-id', String(noteEditorId)); } catch (_) {}
  noteRightRows = (note.rightRows || []).map(r => ({ qty: r.qty || '', detail: r.detail || '' }));
  noteLeftRows = (note.leftRows || []).map(r => ({ qty: r.qty || '', detail: r.detail || '' }));
  if (!noteRightRows.length) noteRightRows = [{ qty: '', detail: '' }];
  if (!noteLeftRows.length) noteLeftRows = [{ qty: '', detail: '' }];
  const lastRight = noteRightRows[noteRightRows.length - 1];
  const lastLeft = noteLeftRows[noteLeftRows.length - 1];
  if (lastRight.qty || lastRight.detail) noteRightRows.push({ qty: '', detail: '' });
  if (lastLeft.qty || lastLeft.detail) noteLeftRows.push({ qty: '', detail: '' });
  renderNoteEditor();
  const scroll = document.getElementById('main-scroll'); if (scroll) scroll.scrollTop = 0;
  restoreNoteEditorCursor(noteEditorId);
}
function removeSavedNote(id) {
  if (!window.Android || !confirm('هل تريد حذف هذه الملاحظة نهائياً؟')) return;
  if (Number(noteEditorId) === Number(id)) {
    clearTimeout(noteAutosaveTimer);
    noteEditorSession++;
    noteEditorId = 0; noteEditorDirty = false; noteRightRows = [{ qty: '', detail: '' }]; noteLeftRows = [{ qty: '', detail: '' }];
    try { localStorage.setItem('alaziy-last-note-id', '0'); } catch (_) {}
    renderNoteEditor();
  }
  window.Android.deleteNote(id, nextReqId());
}
function openNoteHistory() {
  refreshNotesHistoryAfterSave = noteEditorDirty || noteAutosaveInFlight;
  switchView('notes-history');
}
function requireSavedNote() {
  if (!noteEditorId) {
    if (noteHasContent()) {
      clearTimeout(noteAutosaveTimer);
      performNoteSave(false);
      showToast('جارٍ حفظ الملاحظة تلقائياً؛ أعد المحاولة بعد لحظة', 'info');
    } else {
      showToast('اكتب محتوى الملاحظة أولاً');
    }
    return false;
  }
  if (noteEditorDirty || noteAutosaveInFlight) {
    clearTimeout(noteAutosaveTimer);
    performNoteSave(false);
    showToast('جارٍ حفظ التعديلات تلقائياً؛ أعد المحاولة بعد لحظة', 'info');
    return false;
  }
  return true;
}
function toggleNoteThermalPreview(forceState) {
  const card = document.getElementById('note-thermal-preview-card');
  const btn = document.getElementById('btn-note-preview-toggle');
  if (!card) return;
  notePreviewVisible = typeof forceState === 'boolean' ? forceState : card.classList.contains('hidden');
  card.classList.toggle('hidden', !notePreviewVisible);
  if (btn) btn.textContent = notePreviewVisible ? 'إخفاء المعاينة' : 'معاينة حرارية';
  if (notePreviewVisible) {
    refreshNoteThermalPreview();
    card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
}
function refreshNoteThermalPreview() {
  const card = document.getElementById('note-thermal-preview-card');
  if (!card || !window.Android || !window.Android.previewNoteThermalDraft) return;
  const payload = {
    id: noteEditorId || 0,
    rightRows: notePayloadRows(noteRightRows),
    leftRows: notePayloadRows(noteLeftRows),
    updatedAt: Date.now()
  };
  const reqId = nextReqId(data => {
    if (!data || !data.imageBase64) return;
    const img = document.getElementById('note-thermal-preview-img');
    const badge = document.getElementById('note-preview-width-badge');
    const status = document.getElementById('note-preview-printer-status');
    const b58 = document.getElementById('note-preview-58-btn');
    const b80 = document.getElementById('note-preview-80-btn');
    if (img) img.src = 'data:image/png;base64,' + data.imageBase64;
    if (badge) badge.textContent = `${data.widthMm} مم`;
    if (b58) b58.classList.toggle('is-active', Number(data.widthMm) === 58);
    if (b80) b80.classList.toggle('is-active', Number(data.widthMm) === 80);
    if (status) {
      status.textContent = data.connected
        ? `متصل بالطابعة: ${data.printerName || 'طابعة بلوتوث'}`
        : 'الطابعة غير متصلة حالياً — المعاينة جاهزة، يمكنك الاتصال بالطابعة من الإعدادات';
    }
  });
  window.Android.previewNoteThermalDraft(JSON.stringify(payload), reqId);
}
function switchNotePreviewWidth(widthMm) {
  if (!window.Android) return;
  window.Android.setPrinterWidth(widthMm, nextReqId(() => {
    refreshNoteThermalPreview();
  }));
}
function openFullNoteThermalPreview() {
  if (!window.Android || !requireSavedNote()) return;
  thermalReturnView = 'notes';
  window.Android.previewGenericThermal('NOTE', noteEditorId, '', 0, 0, nextReqId());
}
function saveNoteThermalImage() {
  if (!window.Android || !requireSavedNote()) return;
  window.Android.saveGenericThermalToAppFolder('NOTE', noteEditorId, '', 0, 0, nextReqId());
}
function printCurrentNote() {
  if (!window.Android || !requireSavedNote()) return;
  thermalReturnView = 'notes';
  // Always open the full thermal preview first; printing happens only from its Print button.
  window.Android.previewGenericThermal('NOTE', noteEditorId, '', 0, 0, nextReqId());
}
function exportCurrentNotePdf() { if (window.Android && requireSavedNote()) window.Android.exportNotePdf(noteEditorId, nextReqId()); }
function shareCurrentNoteWhatsApp() { if (window.Android && requireSavedNote()) window.Android.shareNoteWhatsApp(noteEditorId, nextReqId()); }
function exportCurrentNoteImageHd() { if (window.Android && requireSavedNote()) window.Android.exportNoteImageHd(noteEditorId, nextReqId()); }
function shareCurrentNoteImageWhatsApp() { if (window.Android && requireSavedNote()) window.Android.shareNoteImageWhatsApp(noteEditorId, nextReqId()); }
function shareCurrentNoteThermalImageWhatsApp() { if (window.Android && requireSavedNote()) window.Android.shareNoteThermalImageWhatsApp(noteEditorId, nextReqId()); }

// ---------------- Reports ----------------

function setReportRange(range, btn) {
  reportRange = range;
  document.querySelectorAll('#report-range-tabs .pill').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  loadReports();
}

function getReportRangeBounds() {
  const now = Date.now();
  let start = 0;
  let label = 'الكل';
  if (reportRange === 'TODAY') {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    start = d.getTime();
    label = 'اليوم';
  } else if (reportRange === 'WEEK') {
    start = now - 7 * 24 * 60 * 60 * 1000;
    label = 'آخر 7 أيام';
  } else if (reportRange === 'MONTH') {
    const d = new Date();
    d.setDate(1);
    d.setHours(0, 0, 0, 0);
    start = d.getTime();
    label = 'هذا الشهر';
  }
  return { start, now, label };
}

function loadReports() {
  if (!window.Android) return;
  const { start, now } = getReportRangeBounds();
  window.Android.getReports(start, now);
}

function exportCurrentReportPdf() {
  if (!window.Android) return;
  const { start, now, label } = getReportRangeBounds();
  window.Android.exportFinancialReportPdf(start, now, label, nextReqId());
}

function shareCurrentReportWhatsApp() {
  if (!window.Android) return;
  const { start, now, label } = getReportRangeBounds();
  window.Android.shareReportWhatsAppPdf(start, now, label, nextReqId());
}

function previewReportThermal() {
  if (!window.Android) return;
  thermalReturnView = 'reports';
  verifiedPreviewSignature = '';
  const btn = document.getElementById('btn-thermal-print-now');
  if (btn) btn.disabled = true;
  const { start, now, label } = getReportRangeBounds();
  window.Android.previewGenericThermal('REPORT', 0, label, start, now, nextReqId());
}

window.onReportsData = function (jsonStr) {
  try {
    const data = JSON.parse(jsonStr);
    const container = document.getElementById('reports-list');
    const topItemsHtml = (data.topItems || []).length === 0
      ? '<div class="muted-text">لا توجد مبيعات في هذه الفترة</div>'
      : `<div class="table-wrap"><table class="data-table ruled-table"><thead><tr><th>الصنف</th><th>الكمية</th><th>الإيراد</th></tr></thead><tbody>` +
        data.topItems.map(item => `<tr><td>${escapeHtml(item.name)}</td><td>${item.quantity}</td><td><strong>${fmtMoney(item.revenue)} ${escapeHtml(appCurrency)}</strong></td></tr>`).join('') +
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
        ${settingsData.showUncostedProfitWarning !== false && Number(data.uncostedSalesLineCount || 0) > 0 ? `<div class="info-pill" style="grid-column:1/-1;color:var(--danger);">تنبيه: ${Number(data.uncostedSalesLineCount)} سطر بيع لا تتوفر له تكلفة محفوظة؛ الربح المعروض غير مكتمل. راجع فواتير البيع القديمة.</div>` : ''}
        <div class="metric-card"><div class="title">تكلفة البضاعة المباعة</div><div class="value text-dark">${fmtMoney(data.cogs)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">إجمالي المشتريات</div><div class="value text-dark">${fmtMoney(data.purchases)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">ديون العملاء</div><div class="value text-danger">${fmtMoney(data.debts)} ${escapeHtml(appCurrency)}</div></div>
        <div class="metric-card"><div class="title">مستحقات الموردين</div><div class="value text-primary">${fmtMoney(data.payables)} ${escapeHtml(appCurrency)}</div></div>
      </div>

      <div class="card compact-card">
        <h3>ملخص السيولة والمخزون</h3>
        <div class="info-pill">تحصيل نقدي من المبيعات: <strong>${fmtMoney(data.cashSales)} ${escapeHtml(appCurrency)}</strong></div>
        <div class="info-pill">مبيعات آجلة غير محصلة: <strong class="text-danger">${fmtMoney(data.creditSales)} ${escapeHtml(appCurrency)}</strong></div>
        <div class="info-pill">قيمة المخزون بالتكلفة: <strong>${fmtMoney(data.inventoryVal)} ${escapeHtml(appCurrency)}</strong></div>
        <div class="info-pill">قيمة المخزون بسعر البيع: <strong class="text-success">${fmtMoney(data.inventoryRetailVal)} ${escapeHtml(appCurrency)}</strong></div>
      </div>

      <div class="card compact-card">
        <h3>الأصناف الأكثر مبيعاً</h3>
        <div style="margin-top:6px;">${topItemsHtml}</div>
      </div>

      <div class="card compact-card">
        <h3>أعلى العملاء مديونية</h3>
        <div style="margin-top:6px;">${topDebtorsHtml}</div>
      </div>
    `;
  } catch (e) {
    console.error(e);
  }
};

// ---------------- Advanced 7-Section Settings, Bluetooth & Backup ----------------

function applyUiPreferences() {
  if (!document.body) return;
  const scale = (settingsData.uiTextScale || 'NORMAL').toUpperCase();
  document.body.classList.toggle('text-scale-small', scale === 'SMALL');
  document.body.classList.toggle('text-scale-large', scale === 'LARGE');
  document.body.classList.toggle('compact-lists', Boolean(settingsData.compactLists));
}

function scrollToSettingsSection(sectionId, btn) {
  document.querySelectorAll('#settings-section-pills .pill').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  const el = document.getElementById(sectionId);
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function setCheckboxValue(id, val) {
  const el = document.getElementById(id);
  if (el) el.checked = Boolean(val);
}

function setControlValue(id, val) {
  const el = document.getElementById(id);
  if (el) {
    const value = val ?? '';
    if (id === 'set-default-inventory-unit' && value && el.tagName === 'SELECT' && ![...el.options].some(option => option.value === String(value))) {
      const legacy = document.createElement('option'); legacy.value = String(value); legacy.textContent = String(value); el.appendChild(legacy);
    }
    el.value = value;
  }
}

window.onSettingsData = function (jsonStr) {
  try {
    const incoming = JSON.parse(jsonStr);
    settingsData = { ...settingsData, ...incoming };
    refreshUnitSelectOptions(document);
    appCurrency = settingsData.currency || appCurrency;
    storeName = settingsData.storeName || storeName;
    document.getElementById('app-title').textContent = storeName;
    document.getElementById('drawer-store-name').textContent = storeName;

    applyUiPreferences();

    if (!settingsLoadedOnce) {
      settingsLoadedOnce = true;
      inventorySort = settingsData.defaultInventorySort || 'NAME';
      const invSortEl = document.getElementById('inventory-sort');
      if (invSortEl) invSortEl.value = inventorySort;

      reportRange = settingsData.defaultReportRange || 'ALL';
      const rangeOrder = ['ALL', 'TODAY', 'WEEK', 'MONTH'];
      document.querySelectorAll('#report-range-tabs .pill').forEach((p, idx) => {
        p.classList.toggle('active', rangeOrder[idx] === reportRange);
      });
    }

    // Section 1: General
    setControlValue('set-store-name', settingsData.storeName || '');
    setControlValue('set-store-phone', settingsData.storePhone || '');
    setControlValue('set-store-address', settingsData.storeAddress || '');
    setControlValue('set-currency', settingsData.currency || 'ر.ي');
    setControlValue('set-ui-text-scale', settingsData.uiTextScale || 'NORMAL');
    setCheckboxValue('set-compact-lists', settingsData.compactLists);

    // Section 2: Invoices
    setControlValue('set-default-invoice-type', settingsData.defaultInvoiceType || 'CASH');
    setControlValue('set-quick-party-count', settingsData.quickPartyCount ?? 5);
    setCheckboxValue('set-auto-fill-cash-paid', settingsData.autoFillCashPaid !== false);
    setCheckboxValue('set-confirm-delete-invoice', settingsData.confirmBeforeDeleteInvoice !== false);

    // Section 3: Inventory
    setControlValue('set-low-stock-threshold', settingsData.lowStockThreshold ?? 10);
    setControlValue('set-default-inventory-sort', settingsData.defaultInventorySort || 'NAME');
    setControlValue('set-default-inventory-unit', settingsData.defaultInventoryUnit || 'حبة');
    setControlValue('set-default-inventory-category', settingsData.defaultInventoryCategory || 'مواد غذائية');

    // Section 4: Customers & Suppliers
    setControlValue('set-default-customer-sort', settingsData.defaultCustomerSort || 'NAME');
    setCheckboxValue('set-prompt-wa-tx', settingsData.promptWhatsAppAfterTx !== false);
    setCheckboxValue('set-show-zero-balance', settingsData.showZeroBalanceParties !== false);

    // Section 5: Reports
    setControlValue('set-default-report-range', settingsData.defaultReportRange || 'ALL');
    setCheckboxValue('set-show-uncosted-warning', settingsData.showUncostedProfitWarning !== false);

    // Section 6: Printing & Sharing
    const w58 = document.getElementById('width-btn-58');
    const w80 = document.getElementById('width-btn-80');
    if (w58) w58.className = settingsData.printerWidthMm === 58 ? 'btn btn-primary btn-compact' : 'btn btn-outline btn-compact';
    if (w80) w80.className = settingsData.printerWidthMm === 80 ? 'btn btn-primary btn-compact' : 'btn btn-outline btn-compact';
    const statusEl = document.getElementById('printer-status-text');
    if (statusEl) {
      statusEl.textContent = settingsData.printerConnected
        ? `متصل بالطابعة: ${settingsData.connectedPrinterName || settingsData.connectedPrinterAddress}`
        : 'حالة الطابعة: غير متصل';
    }
    const discBtn = document.getElementById('btn-disconnect-printer');
    if (discBtn) discBtn.classList.toggle('hidden', !settingsData.printerConnected);
    setCheckboxValue('set-require-thermal-preview', settingsData.requireThermalPreview !== false);
    setCheckboxValue('set-auto-save-thermal-img', settingsData.autoSaveThermalImage);
    setCheckboxValue('set-auto-open-pdf', settingsData.autoOpenPdfAfterExport !== false);
    setCheckboxValue('set-prefer-wa-direct', settingsData.preferWhatsAppDirect !== false);

    // Section 7: Backup & Folder Status (NO saved-files list)
    const folderPill = document.getElementById('app-folder-status-pill');
    if (folderPill) {
      const linkNote = settingsData.linkedFolderActive ? 'مجلد مخصص مرتبط ✓' : 'حفظ تلقائي في التنزيلات ✓';
      folderPill.textContent = `المسار: ${settingsData.folderDisplayPath || 'Download/بقاله العزي'}  •  ${linkNote}`;
    }
    const linkBtn = document.getElementById('btn-link-app-folder');
    if (linkBtn) {
      linkBtn.textContent = settingsData.linkedFolderActive ? 'إلغاء ربط المجلد' : 'ربط مجلد بقاله العزي';
    }
    const safetyRow = document.getElementById('safety-backup-row');
    if (safetyRow) {
      safetyRow.classList.toggle('hidden', !settingsData.hasSafetyBackup);
    }

    if (currentView === 'inventory') renderInventoryList();
    if (currentView === 'customers') renderCustomersList();
    if (currentView === 'suppliers') renderSuppliersList();
    if (currentView === 'reports') loadReports();
  } catch (e) {
    console.error(e);
  }
};

function saveStoreSettings() {
  saveAllAdvancedSettings();
}

function saveAllAdvancedSettings() {
  if (!window.Android) return;
  const quickCountRaw = parseInt(document.getElementById('set-quick-party-count')?.value, 10);
  const lowThresholdRaw = parseInt(document.getElementById('set-low-stock-threshold')?.value, 10);
  const payload = {
    storeName: (document.getElementById('set-store-name')?.value || '').trim() || 'بقالة العزي للمواد الغذائية',
    storePhone: (document.getElementById('set-store-phone')?.value || '').trim(),
    storeAddress: (document.getElementById('set-store-address')?.value || '').trim(),
    currency: (document.getElementById('set-currency')?.value || '').trim() || 'ر.ي',
    uiTextScale: document.getElementById('set-ui-text-scale')?.value || 'NORMAL',
    compactLists: Boolean(document.getElementById('set-compact-lists')?.checked),
    defaultInvoiceType: document.getElementById('set-default-invoice-type')?.value || 'CASH',
    quickPartyCount: Number.isFinite(quickCountRaw) ? Math.max(3, Math.min(12, quickCountRaw)) : 5,
    autoFillCashPaid: Boolean(document.getElementById('set-auto-fill-cash-paid')?.checked),
    confirmBeforeDeleteInvoice: Boolean(document.getElementById('set-confirm-delete-invoice')?.checked),
    lowStockThreshold: Number.isFinite(lowThresholdRaw) ? Math.max(1, Math.min(500, lowThresholdRaw)) : 10,
    defaultInventorySort: document.getElementById('set-default-inventory-sort')?.value || 'NAME',
    defaultInventoryUnit: (document.getElementById('set-default-inventory-unit')?.value || '').trim() || 'حبة',
    defaultInventoryCategory: (document.getElementById('set-default-inventory-category')?.value || '').trim() || 'مواد غذائية',
    defaultCustomerSort: document.getElementById('set-default-customer-sort')?.value || 'NAME',
    promptWhatsAppAfterTx: Boolean(document.getElementById('set-prompt-wa-tx')?.checked),
    showZeroBalanceParties: Boolean(document.getElementById('set-show-zero-balance')?.checked),
    defaultReportRange: document.getElementById('set-default-report-range')?.value || 'ALL',
    showUncostedProfitWarning: Boolean(document.getElementById('set-show-uncosted-warning')?.checked),
    printerWidthMm: settingsData.printerWidthMm === 80 ? 80 : 58,
    requireThermalPreview: Boolean(document.getElementById('set-require-thermal-preview')?.checked),
    autoSaveThermalImage: Boolean(document.getElementById('set-auto-save-thermal-img')?.checked),
    autoOpenPdfAfterExport: Boolean(document.getElementById('set-auto-open-pdf')?.checked),
    preferWhatsAppDirect: Boolean(document.getElementById('set-prefer-wa-direct')?.checked)
  };
  inventorySort = payload.defaultInventorySort;
  const invSortEl = document.getElementById('inventory-sort');
  if (invSortEl) invSortEl.value = inventorySort;

  reportRange = payload.defaultReportRange;
  const rangeOrder = ['ALL', 'TODAY', 'WEEK', 'MONTH'];
  document.querySelectorAll('#report-range-tabs .pill').forEach((p, idx) => {
    p.classList.toggle('active', rangeOrder[idx] === reportRange);
  });

  window.Android.saveAdvancedSettings(JSON.stringify(payload), nextReqId());
}

function askResetSettings(show) {
  const box = document.getElementById('reset-settings-confirm');
  if (box) box.classList.toggle('hidden', !show);
}

function confirmResetSettings() {
  if (!window.Android) return;
  settingsLoadedOnce = false;
  window.Android.resetAdvancedSettings(nextReqId(() => askResetSettings(false)));
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
    if (!container) return;
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
      const cls = isActive ? 'btn btn-success btn-compact' : 'btn btn-outline btn-compact';
      const suffix = isActive ? ' (متصل)' : '';
      return `<button type="button" class="${cls}" style="margin-top:5px; justify-content:space-between; text-align:right;" onclick="connectPrinter('${escapeHtml(d.address)}')"><span>${escapeHtml(d.name)}${suffix}</span><span class="cell-num" style="font-size:11px; opacity:0.8;">${escapeHtml(d.address)}</span></button>`;
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

function exportBackupToFolder() {
  if (!window.Android) return;
  window.Android.exportBackupToAppFolder(nextReqId());
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

function linkAppFolder() {
  if (!window.Android) return;
  if (settingsData.linkedFolderActive) {
    window.Android.unlinkAppFolder(nextReqId());
  } else {
    window.Android.linkAppFolder();
  }
}

function toggleSafetyRestoreConfirm(show) {
  const box = document.getElementById('restore-safety-confirm');
  if (box) box.classList.toggle('hidden', !show);
}

function confirmRestoreSafetyBackup() {
  if (!window.Android) return;
  window.Android.restoreSafetyBackup(nextReqId(() => toggleSafetyRestoreConfirm(false)));
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
  if (window.Android && window.Android.getSettings) {
    window.Android.getSettings();
  }
  switchView('dashboard');
};
