/* Android/Bionic port: stub for net_init_slirp when libslirp is unavailable.
 * The ESP32 machine does not use user-mode networking. */
#include "qemu/osdep.h"
#include "qapi/error.h"
#include "net/clients.h"
#include "net/slirp.h"
#include "monitor/monitor.h"
#include "qapi/qmp/qdict.h"

int net_init_slirp(const Netdev *netdev, const char *name,
                   NetClientState *peer, Error **errp)
{
    error_setg(errp, "slirp networking not available on Android");
    return -1;
}

void hmp_info_usernet(Monitor *mon, const QDict *qdict)
{
    monitor_printf(mon, "slirp networking not available on Android\n");
}

void hmp_hostfwd_add(Monitor *mon, const QDict *qdict)
{
    monitor_printf(mon, "slirp networking not available on Android\n");
}

void hmp_hostfwd_remove(Monitor *mon, const QDict *qdict)
{
    monitor_printf(mon, "slirp networking not available on Android\n");
}
