#include <zephyr/types.h>
#include <stddef.h>
#include <string.h>

#include <zephyr/sys/printk.h>
#include <zephyr/sys/util.h>
#include <zephyr/sys/byteorder.h>

#include <zephyr/kernel.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/hci_vs.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>

#include <zephyr/net_buf.h>

/*
 * Custom service:
 *   Service UUID:        12345678-1234-5678-1234-56789abcdef0
 *   Interval Char UUID:  12345678-1234-5678-1234-56789abcdef1
 *   TX Power Char UUID:  12345678-1234-5678-1234-56789abcdef2
 *
 * Interval characteristic value format:
 *   uint16_t, little-endian, units = milliseconds
 *
 * TX power characteristic value format:
 *   int8_t, units = dBm
 */

#define URBANNAV_SERVICE_UUID_VAL \
	BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234, 0x56789abcdef0)

#define ADV_INTERVAL_CHAR_UUID_VAL \
	BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234, 0x56789abcdef1)

#define ADV_TX_POWER_CHAR_UUID_VAL \
	BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234, 0x56789abcdef2)

static struct bt_uuid_128 urbannav_service_uuid =
        BT_UUID_INIT_128(URBANNAV_SERVICE_UUID_VAL);

static struct bt_uuid_128 adv_interval_char_uuid =
        BT_UUID_INIT_128(ADV_INTERVAL_CHAR_UUID_VAL);

static struct bt_uuid_128 adv_tx_power_char_uuid =
        BT_UUID_INIT_128(ADV_TX_POWER_CHAR_UUID_VAL);

static const uint8_t urbannav_service_uuid_bytes[] = {
        URBANNAV_SERVICE_UUID_VAL
};

static struct bt_le_ext_adv *adv_set;

/* Advertising data */
static const struct bt_data ad[] = {
        BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
        BT_DATA(BT_DATA_UUID128_ALL,
                urbannav_service_uuid_bytes,
                sizeof(urbannav_service_uuid_bytes)),
        // Move the name here so it's in the primary payload
        BT_DATA(BT_DATA_NAME_COMPLETE,
                CONFIG_BT_DEVICE_NAME,
                sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

/* Keep scan response empty (required for Connectable Extended Advertising) */
static const struct bt_data sd[] = {};

/* Current settings */
static uint16_t adv_interval_ms = 100;
static int8_t adv_tx_power_dbm = 8;

/* Track connection */
static struct bt_conn *current_conn = NULL;

/*
 * FIX: Use a work queue item to restart advertising after disconnect.
 * Calling bt_le_adv_start() directly inside the disconnected() callback
 * can fail in Zephyr 4.x because the BT stack hasn't fully released the
 * connection resources yet when the callback fires. Deferring to the
 * system work queue ensures the stack is ready.
 */
static struct k_work adv_restart_work;

static uint16_t ms_to_adv_units(uint16_t ms)
{
    uint32_t units = ((uint32_t)ms * 8U) / 5U;

    if (units < 0x0020) {
        units = 0x0020;   /* 20 ms */
    }
    if (units > 0x4000) {
        units = 0x4000;   /* 10.24 s */
    }

    return (uint16_t)units;
}

static int set_adv_tx_power(int8_t tx_power_dbm)
{
    struct net_buf *buf;
    struct net_buf *rsp = NULL;
    struct bt_hci_cp_vs_write_tx_power_level *cp;
    struct bt_hci_rp_vs_write_tx_power_level *rp;
    int err;

    buf = bt_hci_cmd_alloc(K_NO_WAIT);
    if (!buf) {
        printk("Failed to allocate HCI command buffer\n");
        return -ENOMEM;
    }

    cp = net_buf_add(buf, sizeof(*cp));
    cp->handle_type = BT_HCI_VS_LL_HANDLE_TYPE_ADV;
    cp->handle = sys_cpu_to_le16(0);
    cp->tx_power_level = tx_power_dbm;

    err = bt_hci_cmd_send_sync(BT_HCI_OP_VS_WRITE_TX_POWER_LEVEL, buf, &rsp);
    if (err) {
        printk("Failed to set advertising TX power (err %d)\n", err);
        return err;
    }

    rp = (struct bt_hci_rp_vs_write_tx_power_level *)rsp->data;

    if (rp->status) {
        printk("Controller rejected TX power change (status 0x%02x)\n",
               rp->status);
        net_buf_unref(rsp);
        return -EIO;
    }

    printk("Advertising TX power set: requested=%d dBm, selected=%d dBm\n",
           tx_power_dbm, rp->selected_tx_power);

    net_buf_unref(rsp);
    return 0;
}

static int start_advertising(uint16_t interval_ms)
{
    int err;
    uint32_t interval_units = ms_to_adv_units(interval_ms);

    struct bt_le_adv_param adv_param = {
            .id = BT_ID_DEFAULT,
            .sid = 0,
            .secondary_max_skip = 0,
            // MUST be Connectable AND Extended for your GATT settings to work
            .options = BT_LE_ADV_OPT_CONN | BT_LE_ADV_OPT_EXT_ADV,
            .interval_min = interval_units,
            .interval_max = interval_units,
            .peer = NULL,
    };

    // 1. Create the set
    err = bt_le_ext_adv_create(&adv_param, NULL, &adv_set);
    if (err) {
        printk("Failed to create advertising set (err %d)\n", err);
        return err;
    }

    // 2. Set the data (Notice: sd is now NULL and 0)
    err = bt_le_ext_adv_set_data(adv_set, ad, ARRAY_SIZE(ad), NULL, 0);
    if (err) {
        printk("Failed to set advertising data (err %d)\n", err);
        return err;
    }

    // 3. Start it
    err = bt_le_ext_adv_start(adv_set, BT_LE_EXT_ADV_START_DEFAULT);
    if (err) {
        printk("Failed to start advertising (err %d)\n", err);
        return err;
    }

    printk("Extended Advertising Active. (Connectable, Name in Primary Payload)\n");

    // Re-apply TX power
    set_adv_tx_power(adv_tx_power_dbm);

    return 0;
}

/* Work handler — runs in system work queue context, safely after disconnect */
static void adv_restart_work_handler(struct k_work *work)
{
    ARG_UNUSED(work);

    printk("Restarting advertising...\n");

    if (start_advertising(adv_interval_ms)) {
        printk("Failed to restart advertising after disconnect\n");
    }
}

static ssize_t read_adv_interval(struct bt_conn *conn,
                                 const struct bt_gatt_attr *attr,
                                 void *buf,
                                 uint16_t len,
                                 uint16_t offset)
{
    uint16_t value_le = sys_cpu_to_le16(adv_interval_ms);

    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             &value_le, sizeof(value_le));
}

static ssize_t write_adv_interval(struct bt_conn *conn,
                                  const struct bt_gatt_attr *attr,
                                  const void *buf,
                                  uint16_t len,
                                  uint16_t offset,
                                  uint8_t flags)
{
    ARG_UNUSED(conn);
    ARG_UNUSED(attr);
    ARG_UNUSED(flags);

    if (offset != 0) {
        return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
    }

    if (len != sizeof(uint16_t)) {
        return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
    }

    uint16_t value_le;
    memcpy(&value_le, buf, sizeof(value_le));

    uint16_t new_interval_ms = sys_le16_to_cpu(value_le);

    if (new_interval_ms < 20 || new_interval_ms > 10000) {
        printk("Rejected interval: %u ms\n", new_interval_ms);
        return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
    }

    adv_interval_ms = new_interval_ms;

    printk("New advertising interval received over GATT: %u ms\n",
           adv_interval_ms);
    printk("It will take effect after disconnect.\n");

    return len;
}

static ssize_t read_adv_tx_power(struct bt_conn *conn,
                                 const struct bt_gatt_attr *attr,
                                 void *buf,
                                 uint16_t len,
                                 uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             &adv_tx_power_dbm, sizeof(adv_tx_power_dbm));
}

static ssize_t write_adv_tx_power(struct bt_conn *conn,
                                  const struct bt_gatt_attr *attr,
                                  const void *buf,
                                  uint16_t len,
                                  uint16_t offset,
                                  uint8_t flags)
{
    ARG_UNUSED(conn);
    ARG_UNUSED(attr);
    ARG_UNUSED(flags);

    if (offset != 0) {
        return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
    }

    if (len != sizeof(int8_t)) {
        return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
    }

    int8_t new_tx_power;
    memcpy(&new_tx_power, buf, sizeof(new_tx_power));

    if (new_tx_power < -40 || new_tx_power > 8) {
        printk("Rejected TX power: %d dBm\n", new_tx_power);
        return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
    }

    adv_tx_power_dbm = new_tx_power;

    printk("New advertising TX power received over GATT: %d dBm\n",
           adv_tx_power_dbm);
    printk("It will take effect after disconnect.\n");

    return len;
}

BT_GATT_SERVICE_DEFINE(urbannav_svc,
        BT_GATT_PRIMARY_SERVICE(&urbannav_service_uuid),

BT_GATT_CHARACTERISTIC(&adv_interval_char_uuid.uuid,
BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE,
BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
read_adv_interval, write_adv_interval, NULL),

BT_GATT_CHARACTERISTIC(&adv_tx_power_char_uuid.uuid,
BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE,
BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
read_adv_tx_power, write_adv_tx_power, NULL),
);

static void connected(struct bt_conn *conn, uint8_t err)
{
    if (err) {
        printk("Connection failed (err 0x%02x)\n", err);
        return;
    }

    current_conn = bt_conn_ref(conn);
    printk("Phone connected\n");
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    ARG_UNUSED(conn);

    printk("Phone disconnected (reason 0x%02x)\n", reason);

    if (current_conn) {
        bt_conn_unref(current_conn);
        current_conn = NULL;
    }

    /* FIX: Defer advertising restart to work queue instead of calling
     * start_advertising() directly here. This avoids a race condition
     * in Zephyr 4.x where the BT stack hasn't finished releasing
     * connection resources by the time this callback fires.
     */
    k_work_submit(&adv_restart_work);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
        .connected = connected,
        .disconnected = disconnected,
};

int main(void)
{
    int err;
    printk("Starting UrbanNAV Configurable Beacon\n");

    /* Initialize the advertising restart work item */
    k_work_init(&adv_restart_work, adv_restart_work_handler);

    err = bt_enable(NULL);
    if (err) {
        printk("Bluetooth init failed (err %d)\n", err);
        return 1;
    }

    printk("Bluetooth initialized\n");

    err = start_advertising(adv_interval_ms);
    if (err) {
        return 1;
    }

    printk("Advertising now. Connect from phone to change interval or TX power.\n");

    while (1) {
        k_sleep(K_FOREVER);
    }

    return 0;
}
