package com.example

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.ui.theme.*
import com.example.utils.*
import com.example.viewmodel.GroceryViewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

  private val bluetoothPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
      val granted = permissions.values.all { it }
      if (!granted) {
        Toast.makeText(this, "أذونات البلوتوث مطلوبة للطباعة", Toast.LENGTH_SHORT).show()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    checkBluetoothPermissions()

    setContent {
      MyApplicationTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
          ) {
            MainAppScreen()
          }
        }
      }
    }
  }

  private fun checkBluetoothPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
      val connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
      if (scan != PackageManager.PERMISSION_GRANTED || connect != PackageManager.PERMISSION_GRANTED) {
        bluetoothPermissionLauncher.launch(
          arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        )
      }
    }
  }
}

enum class Screen {
  DASHBOARD,
  CUSTOMERS,
  CUSTOMER_DETAIL,
  SUPPLIERS,
  INVENTORY,
  INVOICES,
  CREATE_INVOICE,
  REPORTS,
  SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
  val viewModel: GroceryViewModel = viewModel()
  var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
  var selectedCustomerId by remember { mutableStateOf<Long?>(null) }
  var selectedInvoiceId by remember { mutableStateOf<Long?>(null) }

  BackHandler(enabled = currentScreen != Screen.DASHBOARD) {
    currentScreen = Screen.DASHBOARD
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Store, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("بقالة العزي للمواد الغذائية", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBlueHeader),
        actions = {
          if (currentScreen != Screen.DASHBOARD) {
            IconButton(onClick = { currentScreen = Screen.DASHBOARD }) {
              Icon(Icons.Default.Home, contentDescription = "الرئيسية", tint = Color.White)
            }
          }
          IconButton(onClick = { currentScreen = Screen.SETTINGS }) {
            Icon(Icons.Default.Settings, contentDescription = "الإعدادات", tint = Color.White)
          }
        }
      )
    },
    bottomBar = {
      NavigationBar(containerColor = SurfaceLight) {
        NavigationBarItem(
          selected = currentScreen == Screen.DASHBOARD,
          onClick = { currentScreen = Screen.DASHBOARD },
          icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
          label = { Text("الرئيسية") }
        )
        NavigationBarItem(
          selected = currentScreen == Screen.CUSTOMERS,
          onClick = { currentScreen = Screen.CUSTOMERS },
          icon = { Icon(Icons.Default.People, contentDescription = null) },
          label = { Text("العملاء") }
        )
        NavigationBarItem(
          selected = currentScreen == Screen.INVENTORY,
          onClick = { currentScreen = Screen.INVENTORY },
          icon = { Icon(Icons.Default.Inventory, contentDescription = null) },
          label = { Text("المخزن") }
        )
        NavigationBarItem(
          selected = currentScreen == Screen.INVOICES,
          onClick = { currentScreen = Screen.INVOICES },
          icon = { Icon(Icons.Default.Receipt, contentDescription = null) },
          label = { Text("الفواتير") }
        )
        NavigationBarItem(
          selected = currentScreen == Screen.REPORTS,
          onClick = { currentScreen = Screen.REPORTS },
          icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
          label = { Text("التقارير") }
        )
      }
    }
  ) { padding ->
    Box(modifier = Modifier.padding(padding)) {
      when (currentScreen) {
        Screen.DASHBOARD -> DashboardScreen(
          onNavigate = { screen -> currentScreen = screen },
          viewModel = viewModel
        )
        Screen.CUSTOMERS -> CustomersScreen(
          viewModel = viewModel,
          onCustomerClick = { customerId ->
            selectedCustomerId = customerId
            currentScreen = Screen.CUSTOMER_DETAIL
          }
        )
        Screen.CUSTOMER_DETAIL -> selectedCustomerId?.let { id ->
          CustomerDetailScreen(viewModel = viewModel, customerId = id, onBack = { currentScreen = Screen.CUSTOMERS })
        }
        Screen.SUPPLIERS -> SuppliersScreen(viewModel = viewModel)
        Screen.INVENTORY -> InventoryScreen(viewModel = viewModel)
        Screen.INVOICES -> InvoicesScreen(
          viewModel = viewModel,
          onCreateInvoice = { selectedInvoiceId = null; currentScreen = Screen.CREATE_INVOICE },
          onEditInvoice = { invoiceId -> selectedInvoiceId = invoiceId; currentScreen = Screen.CREATE_INVOICE }
        )
        Screen.CREATE_INVOICE -> CreateInvoiceScreen(
          viewModel = viewModel,
          editInvoiceId = selectedInvoiceId,
          onBack = { selectedInvoiceId = null; currentScreen = Screen.INVOICES }
        )
        Screen.REPORTS -> ReportsScreen(viewModel = viewModel)
        Screen.SETTINGS -> SettingsScreen(viewModel = viewModel)
      }
    }
  }
}

@Composable
fun DashboardScreen(onNavigate: (Screen) -> Unit, viewModel: GroceryViewModel) {
  val customers by viewModel.customers.collectAsState()
  val suppliers by viewModel.suppliers.collectAsState()
  val inventory by viewModel.inventoryItems.collectAsState()
  val invoices by viewModel.invoices.collectAsState()

  val totalReceivables = customers.sumOf { maxOf(it.balance, 0.0) }
  val totalPayables = suppliers.sumOf { maxOf(it.balance, 0.0) }
  val totalInventoryValue = inventory.sumOf { it.quantity * it.purchasePrice }
  val startOfToday = remember {
    Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis
  }
  val todaySales = invoices
    .filter { it.date >= startOfToday && it.type in setOf("SALE", "CASH", "CREDIT") }
    .sumOf { it.totalAmount }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Text("لوحة التحكم المالية", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader)
    }

    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(title = "مبيعات اليوم", value = "$todaySales ر.ي", color = SuccessGreen, modifier = Modifier.weight(1f)) {
          onNavigate(Screen.INVOICES)
        }
        MetricCard(title = "ديون العملاء", value = "$totalReceivables ر.ي", color = DangerRed, modifier = Modifier.weight(1f)) {
          onNavigate(Screen.CUSTOMERS)
        }
      }
    }

    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(title = "مستحقات الموردين", value = "$totalPayables ر.ي", color = PrimaryBlue, modifier = Modifier.weight(1f)) {
          onNavigate(Screen.SUPPLIERS)
        }
        MetricCard(title = "قيمة المخزون", value = "$totalInventoryValue ر.ي", color = DarkBlueHeader, modifier = Modifier.weight(1f)) {
          onNavigate(Screen.INVENTORY)
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(8.dp))
      Text("الخدمات السريعة", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }

    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          onClick = { onNavigate(Screen.CREATE_INVOICE) },
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text("فاتورة جديدة")
        }
        Button(
          onClick = { onNavigate(Screen.CUSTOMERS) },
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
        ) {
          Icon(Icons.Default.PersonAdd, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text("إدارة العملاء")
        }
      }
    }
  }
}

@Composable
fun MetricCard(title: String, value: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Card(
    modifier = modifier.clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = SurfaceLight),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(title, fontSize = 13.sp, color = TextSecondary)
      Spacer(modifier = Modifier.height(6.dp))
      Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
    }
  }
}

@Composable
fun CustomersScreen(viewModel: GroceryViewModel, onCustomerClick: (Long) -> Unit) {
  val customers by viewModel.customers.collectAsState()
  var searchQuery by remember { mutableStateOf("") }
  var showAddDialog by remember { mutableStateOf(false) }

  var name by remember { mutableStateOf("") }
  var phone by remember { mutableStateOf("") }
  var address by remember { mutableStateOf("") }
  var balance by remember { mutableStateOf("") }

  val filtered = customers.filter { it.name.contains(searchQuery) || it.phone.contains(searchQuery) }
  val totalReceivables = customers.sumOf { if (it.balance > 0) it.balance else 0.0 }
  val totalPayables = customers.sumOf { if (it.balance < 0) -it.balance else 0.0 }

  Scaffold(
    bottomBar = {
      Surface(color = DarkBlueHeader, modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("عليه: ${totalReceivables.toInt()} - لك: ${totalPayables.toInt()} يمني", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          IconButton(onClick = { showAddDialog = true }) {
            Icon(Icons.Default.AddCircle, contentDescription = "إضافة", tint = Color.White, modifier = Modifier.size(32.dp))
          }
        }
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
      // Header matching reference screenshot
      Row(
        modifier = Modifier.fillMaxWidth().background(DarkBlueHeader, RoundedCornerShape(4.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
        Text("دين البقالة للعملاء", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.White)
      }
      Spacer(modifier = Modifier.height(8.dp))

      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(filtered) { customer ->
          Card(
            modifier = Modifier.fillMaxWidth().clickable { onCustomerClick(customer.id) },
            colors = CardDefaults.cardColors(containerColor = SurfaceLight),
            shape = RoundedCornerShape(2.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Right: Add button
              IconButton(onClick = { onCustomerClick(customer.id) }, modifier = Modifier.size(36.dp)) {
                Box(modifier = Modifier.size(32.dp).background(DarkBlueHeader, CircleShape), contentAlignment = Alignment.Center) {
                  Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
              }

              // Center-Right: Name & ID badge
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(customer.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                Box(
                  modifier = Modifier.background(Color(0xFFD0E1FD), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text("${customer.id}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader)
                }
              }

              // Left: Balance & arrow indicator
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val isDebit = customer.balance >= 0
                val color = if (isDebit) DangerRed else SuccessGreen
                val arrow = if (isDebit) "▼" else "▲"
                Text("${Math.abs(customer.balance).toInt()}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
                Text(arrow, fontSize = 14.sp, color = color)
              }
            }
          }
        }
      }
    }
  }

  if (showAddDialog) {
    AlertDialog(
      onDismissRequest = { showAddDialog = false },
      title = { Text("إضافة عميل جديد") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم العميل") })
          OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("رقم الهاتف (يمني)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
          OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("العنوان") })
          OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text("الرصيد الافتتاحي") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
      },
      confirmButton = {
        Button(onClick = {
          if (name.isNotBlank()) {
            viewModel.addCustomer(name, phone, address, balance.toDoubleOrNull() ?: 0.0)
            name = ""; phone = ""; address = ""; balance = ""
            showAddDialog = false
          }
        }) { Text("حفظ") }
      },
      dismissButton = {
        TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") }
      }
    )
  }
}

@Composable
fun CustomerDetailScreen(viewModel: GroceryViewModel, customerId: Long, onBack: () -> Unit) {
  val context = LocalContext.current
  val customers by viewModel.customers.collectAsState()
  val customer = customers.find { it.id == customerId } ?: return

  var amount by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }

  Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.Default.ArrowForward, contentDescription = null) }
      Text(customer.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(12.dp))

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceLight)) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("الهاتف: ${customer.phone}")
        Text("العنوان: ${customer.address}")
        Spacer(modifier = Modifier.height(8.dp))
        val color = if (customer.balance > 0) DangerRed else SuccessGreen
        Text("الرصيد الحالي: ${customer.balance} ر.ي", fontWeight = FontWeight.Bold, color = color)
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("إضافة معاملة جديدة", fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("المبلغ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("البيان / التفاصيل") }, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
        onClick = {
          val amt = amount.toDoubleOrNull() ?: 0.0
          if (amt > 0) {
            viewModel.addCustomerTransaction(customer.id, customer.name, amt, isDebit = true, description = description.ifBlank { "دفعة / معاملة" })
            amount = ""; description = ""
          }
        },
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
      ) {
        Text("عليه (-)")
      }
      Button(
        onClick = {
          val amt = amount.toDoubleOrNull() ?: 0.0
          if (amt > 0) {
            viewModel.addCustomerTransaction(customer.id, customer.name, amt, isDebit = false, description = description.ifBlank { "قبض / سداد" })
            amount = ""; description = ""
          }
        },
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
      ) {
        Text("له (+)")
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = {
      WhatsAppHelper.shareText(
        context = context,
        phone = customer.phone,
        message = "السلام عليكم ${customer.name}، رصيدكم الحالي لدى بقالة العزي هو: ${customer.balance} ر.ي."
      )
    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Default.Share, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text("مراسلة عبر واتساب")
    }
  }
}

@Composable
fun SuppliersScreen(viewModel: GroceryViewModel) {
  val suppliers by viewModel.suppliers.collectAsState()
  var showAdd by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  var phone by remember { mutableStateOf("") }
  var company by remember { mutableStateOf("") }
  var balance by remember { mutableStateOf("") }

  Scaffold(
    floatingActionButton = {
      FloatingActionButton(onClick = { showAdd = true }, containerColor = PrimaryBlue) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
      Text("إدارة الموردين", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader)
      Spacer(modifier = Modifier.height(12.dp))
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(suppliers) { supplier ->
          Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
              Column {
                Text(supplier.name, fontWeight = FontWeight.Bold)
                Text(supplier.company, fontSize = 13.sp, color = TextSecondary)
                Text(supplier.phone, fontSize = 12.sp, color = TextSecondary)
              }
              Text("${supplier.balance} ر.ي", fontWeight = FontWeight.Bold, color = PrimaryBlue)
            }
          }
        }
      }
    }
  }

  if (showAdd) {
    AlertDialog(
      onDismissRequest = { showAdd = false },
      title = { Text("إضافة مورد جديد") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم المورد") })
          OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("رقم الهاتف") })
          OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text("الشركة / الوكالة") })
          OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text("الرصيد المستحق") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
      },
      confirmButton = {
        Button(onClick = {
          if (name.isNotBlank()) {
            viewModel.addSupplier(name, phone, company, balance.toDoubleOrNull() ?: 0.0)
            showAdd = false; name = ""; phone = ""; company = ""; balance = ""
          }
        }) { Text("حفظ") }
      },
      dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
    )
  }
}

@Composable
fun InventoryScreen(viewModel: GroceryViewModel) {
  val inventory by viewModel.inventoryItems.collectAsState()
  var showAdd by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  var category by remember { mutableStateOf("") }
  var qty by remember { mutableStateOf("") }
  var buyPrice by remember { mutableStateOf("") }
  var sellPrice by remember { mutableStateOf("") }
  var unit by remember { mutableStateOf("حبة") }

  Scaffold(
    floatingActionButton = {
      FloatingActionButton(onClick = { showAdd = true }, containerColor = PrimaryBlue) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
      Text("إدارة المخزن", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader)
      Spacer(modifier = Modifier.height(12.dp))
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(inventory) { item ->
          Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Column {
                Text(item.name, fontWeight = FontWeight.Bold)
                Text("التصنيف: ${item.category} | الوحدة: ${item.unit}", fontSize = 12.sp, color = TextSecondary)
                Text("شراء: ${item.purchasePrice} | بيع: ${item.sellingPrice}", fontSize = 12.sp, color = TextSecondary)
              }
              val lowStock = item.quantity <= 10
              val badgeColor = if (lowStock) DangerRed else SuccessGreen
              Column(horizontalAlignment = Alignment.End) {
                Text("الكمية: ${item.quantity}", fontWeight = FontWeight.Bold, color = badgeColor)
                if (lowStock) Text("منخفض!", fontSize = 10.sp, color = DangerRed)
              }
            }
          }
        }
      }
    }
  }

  if (showAdd) {
    AlertDialog(
      onDismissRequest = { showAdd = false },
      title = { Text("إضافة صنف جديد للمخزن") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الصنف") })
          OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("التصنيف") })
          OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("الكمية الأولية") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
          OutlinedTextField(value = buyPrice, onValueChange = { buyPrice = it }, label = { Text("سعر الشراء") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
          OutlinedTextField(value = sellPrice, onValueChange = { sellPrice = it }, label = { Text("سعر البيع") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
          OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("الوحدة (حبة، كيس، كرتون)") })
        }
      },
      confirmButton = {
        Button(onClick = {
          if (name.isNotBlank()) {
            viewModel.addInventoryItem(name, category, qty.toIntOrNull() ?: 0, buyPrice.toDoubleOrNull() ?: 0.0, sellPrice.toDoubleOrNull() ?: 0.0, unit)
            showAdd = false; name = ""; category = ""; qty = ""; buyPrice = ""; sellPrice = ""
          }
        }) { Text("حفظ") }
      },
      dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
    )
  }
}

@Composable
fun InvoicesScreen(viewModel: GroceryViewModel, onCreateInvoice: () -> Unit, onEditInvoice: (Long) -> Unit) {
  val invoices by viewModel.invoices.collectAsState()

  Scaffold(
    floatingActionButton = {
      FloatingActionButton(onClick = onCreateInvoice, containerColor = SuccessGreen) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
      Text("سجل الفواتير", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader)
      Spacer(modifier = Modifier.height(12.dp))
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(invoices) { invoice ->
          Card(modifier = Modifier.fillMaxWidth().clickable { onEditInvoice(invoice.id) }) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Column(modifier = Modifier.weight(1f)) {
                Text("فاتورة رقم: ${invoice.invoiceNumber}", fontWeight = FontWeight.Bold)
                Text("الطرف: ${invoice.partyName} | النوع: ${if (invoice.type == "PURCHASE") "شراء" else "بيع"}", fontSize = 12.sp, color = TextSecondary)
                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                Text(dateFormat.format(Date(invoice.date)), fontSize = 11.sp, color = TextSecondary)
              }
              Column(horizontalAlignment = Alignment.End) {
                Text("${invoice.totalAmount} ر.ي", fontWeight = FontWeight.Bold, color = DarkBlueHeader)
                val statusColor = if (invoice.status == "PAID") SuccessGreen else DangerRed
                Text(invoice.status, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Bold)
                Text("اضغط للتعديل", fontSize = 10.sp, color = PrimaryBlue)
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInvoiceScreen(viewModel: GroceryViewModel, editInvoiceId: Long? = null, onBack: () -> Unit) {
  val context = LocalContext.current
  val customers by viewModel.customers.collectAsState()
  val suppliers by viewModel.suppliers.collectAsState()
  val inventory by viewModel.inventoryItems.collectAsState()

  var selectedCustomer by remember { mutableStateOf<CustomerEntity?>(null) }
  var selectedSupplier by remember { mutableStateOf<SupplierEntity?>(null) }
  var customerExpanded by remember { mutableStateOf(false) }
  var invoiceType by remember { mutableStateOf("CASH") }
  val cartItems = remember { mutableStateListOf<InvoiceItemEntity>() }
  var paidAmountStr by remember { mutableStateOf("") }
  var invoiceNumber by remember { mutableStateOf("AZ-${System.currentTimeMillis().toString().takeLast(6)}") }
  var isSaving by remember { mutableStateOf(false) }

  var selectedItem by remember { mutableStateOf<InventoryItemEntity?>(null) }
  var qtyStr by remember { mutableStateOf("1") }
  var customPriceStr by remember { mutableStateOf("") }

  val subtotal = cartItems.sumOf { it.totalPrice }
  val paidAmount = paidAmountStr.toDoubleOrNull() ?: 0.0
  val remaining = subtotal - paidAmount

  LaunchedEffect(editInvoiceId) {
    if (editInvoiceId != null) {
      viewModel.loadInvoiceForEdit(editInvoiceId) { loaded ->
        if (loaded != null) {
          invoiceNumber = loaded.invoice.invoiceNumber
          invoiceType = loaded.invoice.type
          paidAmountStr = loaded.invoice.paidAmount.toString()
          if (loaded.invoice.type == "PURCHASE") {
            selectedSupplier = suppliers.firstOrNull { it.id == loaded.invoice.partyId }
          } else {
            selectedCustomer = customers.firstOrNull { it.id == loaded.invoice.partyId }
          }
          cartItems.clear()
          cartItems.addAll(loaded.items.map { it.copy(id = 0L) })
        }
      }
    }
  }

  Scaffold(
    bottomBar = {
      Surface(color = SurfaceLight, tonalElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("الإجمالي: ${subtotal.toInt()} ريال", fontWeight = FontWeight.Bold, color = SuccessGreen, fontSize = 16.sp)
            Text("متبقي: ${remaining.toInt()} ريال", fontWeight = FontWeight.Bold, color = DangerRed, fontSize = 14.sp)
          }
          Spacer(modifier = Modifier.height(8.dp))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
              onClick = { cartItems.clear() },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray)
            ) {
              Text("مسح", fontSize = 12.sp, color = TextPrimary)
            }
            Button(
              onClick = {
                if (cartItems.isNotEmpty()) {
                  val inv = InvoiceEntity(
                    invoiceNumber = invoiceNumber,
                    type = invoiceType,
                    partyId = selectedCustomer?.id ?: 0L,
                    partyName = selectedCustomer?.name ?: "عميل نقدي",
                    date = System.currentTimeMillis(),
                    totalAmount = subtotal,
                    paidAmount = paidAmount,
                    remainingAmount = remaining,
                    status = if (remaining <= 0) "PAID" else "UNPAID"
                  )
                  val pdfFile = PdfGenerator.generateInvoicePdf(context, inv, cartItems)
                  if (pdfFile != null) {
                    val uri = PdfGenerator.getUriForFile(context, pdfFile)
                    WhatsAppHelper.shareFile(context, uri, "application/pdf", "فاتورة بقالة العزي رقم $invoiceNumber")
                  }
                }
              },
              modifier = Modifier.weight(1.5f),
              colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
              Text("طباعة ومعاينة", fontSize = 11.sp)
            }
            Button(
              onClick = {
                if (cartItems.isNotEmpty() && !isSaving) {
                  isSaving = true
                  val pId = if (invoiceType == "PURCHASE") selectedSupplier?.id ?: 0L else selectedCustomer?.id ?: 0L
                  val pName = if (invoiceType == "PURCHASE") selectedSupplier?.name ?: "" else selectedCustomer?.name ?: "عميل نقدي"
                  val result: (Boolean, String) -> Unit = { success, message ->
                    isSaving = false
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) onBack()
                  }
                  if (editInvoiceId != null) {
                    viewModel.updateInvoice(editInvoiceId, invoiceNumber, invoiceType, pId, pName, cartItems.toList(), paidAmount, result)
                  } else {
                    viewModel.createInvoice(invoiceNumber, invoiceType, pId, pName, cartItems.toList(), paidAmount, result)
                  }
                }
              },
              enabled = !isSaving,
              modifier = Modifier.weight(2f),
              colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
              Text("حفظ التعديل", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
              onClick = { invoiceType = "CASH" },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = if (invoiceType == "CASH") SuccessGreen else Color.LightGray)
            ) {
              Text("نقدي", fontSize = 12.sp)
            }
            Button(
              onClick = { invoiceType = "CREDIT" },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = if (invoiceType == "CREDIT") DangerRed else Color.LightGray)
            ) {
              Text("أجل", fontSize = 12.sp)
            }
          }
        }
      }
    }
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().background(DarkBlueHeader, RoundedCornerShape(4.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.White) }
        Text("تعديل الفاتورة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        IconButton(onClick = {}) { Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.White) }
      }
      Spacer(modifier = Modifier.height(8.dp))

      // Select the account before saving a credit invoice. Cash sales may remain anonymous.
      ExposedDropdownMenuBox(
        expanded = customerExpanded,
        onExpandedChange = { customerExpanded = !customerExpanded }
      ) {
        OutlinedTextField(
          value = selectedCustomer?.name ?: "عميل نقدي / اختر العميل",
          onValueChange = {},
          readOnly = true,
          label = { Text("العميل") },
          modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = customerExpanded) }
        )
        ExposedDropdownMenu(
          expanded = customerExpanded,
          onDismissRequest = { customerExpanded = false }
        ) {
          DropdownMenuItem(
            text = { Text("عميل نقدي بدون حساب") },
            onClick = {
              selectedCustomer = null
              customerExpanded = false
            }
          )
          customers.forEach { customer ->
            DropdownMenuItem(
              text = { Text("${customer.name} - الرصيد: ${customer.balance}") },
              onClick = {
                selectedCustomer = customer
                customerExpanded = false
              }
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(8.dp))

      // Item input row matching screenshot 1
      Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceLight)) {
        Column(modifier = Modifier.padding(8.dp)) {
          var inventoryExpanded by remember { mutableStateOf(false) }
          ExposedDropdownMenuBox(expanded = inventoryExpanded, onExpandedChange = { inventoryExpanded = !inventoryExpanded }) {
            OutlinedTextField(
              value = selectedItem?.name ?: "اسم الصنف / التفاصيل",
              onValueChange = {},
              readOnly = true,
              modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = inventoryExpanded) }
            )
            ExposedDropdownMenu(expanded = inventoryExpanded, onDismissRequest = { inventoryExpanded = false }) {
              inventory.forEach { inv ->
                DropdownMenuItem(
                  text = { Text("${inv.name} (متوفر: ${inv.quantity}) - ${inv.sellingPrice} ر.ي") },
                  onClick = {
                    selectedItem = inv
                    customPriceStr = inv.sellingPrice.toString()
                    inventoryExpanded = false
                  }
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = qtyStr,
              onValueChange = { qtyStr = it },
              label = { Text("العدد") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = customPriceStr,
              onValueChange = { customPriceStr = it },
              label = { Text("السعر") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f)
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          Button(
            onClick = {
              selectedItem?.let { item ->
                val q = qtyStr.toIntOrNull() ?: 1
                val p = customPriceStr.toDoubleOrNull() ?: item.sellingPrice
                cartItems.add(
                  InvoiceItemEntity(
                    invoiceId = 0L,
                    itemId = item.id,
                    itemName = item.name,
                    quantity = q,
                    unitPrice = p,
                    totalPrice = q * p
                  )
                )
                qtyStr = "1"
              }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("إضافة الصنف")
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
      Text("صندوق عرض الفاتورة", fontWeight = FontWeight.Bold, color = DarkBlueHeader, fontSize = 14.sp)
      Spacer(modifier = Modifier.height(4.dp))

      LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(cartItems) { cartItem ->
          Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceLight)) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              TextButton(onClick = { cartItems.remove(cartItem) }) {
                Text("حذف", color = DangerRed, fontSize = 12.sp)
              }
              Text("${cartItem.unitPrice}", fontSize = 14.sp)
              Text(cartItem.itemName, fontSize = 14.sp, fontWeight = FontWeight.Bold)
              Text("${cartItem.quantity}", fontSize = 14.sp)
              Text("${cartItem.totalPrice.toInt()}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader)
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(4.dp))
      OutlinedTextField(
        value = paidAmountStr,
        onValueChange = { paidAmountStr = it },
        label = { Text("المبلغ المدفوع (ريال)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

@Composable
fun ReportsScreen(viewModel: GroceryViewModel) {
  val invoices by viewModel.invoices.collectAsState()
  val customers by viewModel.customers.collectAsState()
  val suppliers by viewModel.suppliers.collectAsState()
  val inventory by viewModel.inventoryItems.collectAsState()

  val totalSales = invoices.filter { it.type in setOf("SALE", "CASH", "CREDIT") }.sumOf { it.totalAmount }
  val totalPurchases = invoices.filter { it.type == "PURCHASE" }.sumOf { it.totalAmount }
  val customerDebts = customers.sumOf { maxOf(it.balance, 0.0) }
  val supplierDues = suppliers.sumOf { maxOf(it.balance, 0.0) }
  val stockVal = inventory.sumOf { it.quantity * it.purchasePrice }

  LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { Text("التقارير المالية والمحاسبية", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader) }
    item { ReportRow("إجمالي المبيعات", "$totalSales ر.ي", SuccessGreen) }
    item { ReportRow("إجمالي المشتريات", "$totalPurchases ر.ي", PrimaryBlue) }
    item { ReportRow("إجمالي ديون العملاء", "$customerDebts ر.ي", DangerRed) }
    item { ReportRow("إجمالي مستحقات الموردين", "$supplierDues ر.ي", PrimaryBlue) }
    item { ReportRow("إجمالي قيمة المخزون", "$stockVal ر.ي", DarkBlueHeader) }
  }
}

@Composable
fun ReportRow(title: String, value: String, color: Color) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(title, fontWeight = FontWeight.Bold)
      Text(value, fontWeight = FontWeight.Bold, color = color)
    }
  }
}

@Composable
fun SettingsScreen(viewModel: GroceryViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val pairedDevices = remember { BluetoothPrinterManager.getPairedDevices() }
  var printerStatus by remember { mutableStateOf(if (BluetoothPrinterManager.isConnected()) "متصل بالطابعة" else "غير متصل") }
  var backupBusy by remember { mutableStateOf(false) }
  var backupMessage by remember { mutableStateOf("احفظ نسخة من جميع بيانات العملاء والموردين والمخزون والفواتير والحركات") }
  var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

  val createBackupLauncher = rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
  ) { uri ->
    if (uri != null) {
      scope.launch {
        backupBusy = true
        try {
          backupMessage = BackupRestoreManager.exportToUri(context, uri)
          Toast.makeText(context, backupMessage, Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
          backupMessage = error.message ?: "تعذر إنشاء النسخة الاحتياطية"
          Toast.makeText(context, backupMessage, Toast.LENGTH_LONG).show()
        } finally {
          backupBusy = false
        }
      }
    }
  }

  val openBackupLauncher = rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) restoreUri = uri
  }

  LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { Text("إعدادات النظام والطابعة والنسخ الاحتياطي", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlueHeader) }

    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text("النسخ الاحتياطي والاستعادة", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = DarkBlueHeader)
          Spacer(modifier = Modifier.height(6.dp))
          Text(backupMessage, fontSize = 13.sp, color = TextSecondary)
          Spacer(modifier = Modifier.height(10.dp))
          Button(
            onClick = {
              val fileName = "Alaziy_Backup_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".json"
              createBackupLauncher.launch(fileName)
            },
            enabled = !backupBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
          ) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (backupBusy) "جارٍ إنشاء النسخة..." else "إنشاء نسخة احتياطية")
          }
          OutlinedButton(
            onClick = { openBackupLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            enabled = !backupBusy,
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("استعادة نسخة احتياطية")
          }
          if (backupBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          Text(
            "تنبيه: الاستعادة تستبدل البيانات الحالية ببيانات الملف المختار. تأكد من حفظ نسخة حديثة قبل الاستعادة.",
            fontSize = 12.sp,
            color = DangerRed
          )
        }
      }
    }

    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text("حالة الطابعة الحرارية: $printerStatus", fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(8.dp))
          Text("الأجهزة المقترنة بالبلوتوث:", fontSize = 13.sp, color = TextSecondary)
          Spacer(modifier = Modifier.height(4.dp))
          if (pairedDevices.isEmpty()) {
            Text("لا توجد أجهزة بلوتوث مقترنة. يرجى إقران طابعة ESC/POS من إعدادات الجهاز.")
          } else {
            pairedDevices.forEach { device ->
              OutlinedButton(
                onClick = {
                  val success = BluetoothPrinterManager.connect(device)
                  if (success) {
                    printerStatus = "متصل بنجاح: ${device.name}"
                    Toast.makeText(context, "تم الاتصال بالطابعة بنجاح", Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(context, "فشل الاتصال بالطابعة", Toast.LENGTH_SHORT).show()
                  }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
              ) {
                Text(device.name ?: "طابعة غير معروفة (${device.address})")
              }
            }
          }
        }
      }
    }

    item {
      Button(
        onClick = {
          val success = BluetoothPrinterManager.printReceiptText(
            title = "تجربة طباعة حرارية",
            details = listOf("صنف 1: 1 × 1000 = 1000", "صنف 2: 2 × 500 = 1000"),
            footer = "الإجمالي: 2000 ر.ي"
          )
          if (success) Toast.makeText(context, "تمت الطباعة بنجاح", Toast.LENGTH_SHORT).show()
          else Toast.makeText(context, "فشل إرسال بيانات الطباعة", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
      ) {
        Icon(Icons.Default.Print, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("طباعة إيصال تجريبي")
      }
    }
  }

  restoreUri?.let { selectedUri ->
    AlertDialog(
      onDismissRequest = { restoreUri = null },
      title = { Text("تأكيد استعادة النسخة") },
      text = { Text("سيتم استبدال بيانات التطبيق الحالية بالبيانات الموجودة في النسخة الاحتياطية. لا يمكن التراجع عن ذلك. هل أنشأت نسخة حديثة من بياناتك الحالية؟") },
      confirmButton = {
        Button(
          enabled = !backupBusy,
          onClick = {
            restoreUri = null
            scope.launch {
              backupBusy = true
              try {
                backupMessage = BackupRestoreManager.restoreFromUri(context, selectedUri)
                Toast.makeText(context, backupMessage, Toast.LENGTH_LONG).show()
              } catch (error: Exception) {
                backupMessage = error.message ?: "تعذرت استعادة النسخة الاحتياطية"
                Toast.makeText(context, backupMessage, Toast.LENGTH_LONG).show()
              } finally {
                backupBusy = false
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) { Text("استعادة واستبدال البيانات") }
      },
      dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("إلغاء") } }
    )
  }
}
