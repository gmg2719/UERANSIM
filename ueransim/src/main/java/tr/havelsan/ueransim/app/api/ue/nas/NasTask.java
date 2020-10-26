/*
 * MIT License
 *
 * Copyright (c) 2020 ALİ GÜNGÖR
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tr.havelsan.ueransim.app.api.ue.nas;


import tr.havelsan.ueransim.app.api.ue.mm.MobilityManagement;
import tr.havelsan.ueransim.app.api.ue.sm.SessionManagement;
import tr.havelsan.ueransim.app.itms.Itms;
import tr.havelsan.ueransim.app.itms.ItmsTask;
import tr.havelsan.ueransim.app.itms.wrappers.IwConnectionRelease;
import tr.havelsan.ueransim.app.itms.wrappers.IwDownlinkNas;
import tr.havelsan.ueransim.app.itms.wrappers.IwNasTimerExpire;
import tr.havelsan.ueransim.app.itms.wrappers.IwUeTestCommand;
import tr.havelsan.ueransim.app.structs.simctx.UeSimContext;
import tr.havelsan.ueransim.app.structs.testcmd.TestCmd;
import tr.havelsan.ueransim.nas.NasDecoder;
import tr.havelsan.ueransim.utils.Tag;
import tr.havelsan.ueransim.utils.console.Log;


public class NasTask extends ItmsTask {

    private final UeSimContext ctx;

    public NasTask(Itms itms, int taskId, UeSimContext ctx) {
        super(itms, taskId);
        this.ctx = ctx;
    }

    private static void executeCommand(UeSimContext ctx, TestCmd cmd) {
        if (!MobilityManagement.executeCommand(ctx, cmd)) {
            if (!SessionManagement.executeCommand(ctx, cmd)) {
                Log.error(Tag.EVENT, "invalid command: %s", cmd);
            }
        }
    }

    @Override
    public void main() {
        // TODO: Make this task sleepable/wakeable like other tasks.
        while (true) {
            MobilityManagement.cycle(ctx);

            var msg = ctx.itms.receiveMessageNonBlocking(this);
            if (msg instanceof IwDownlinkNas) {
                NasTransport.receiveNas(ctx, NasDecoder.nasPdu(((IwDownlinkNas) msg).nasPdu));
            } else if (msg instanceof IwNasTimerExpire) {
                var timer = ((IwNasTimerExpire) msg).timer;
                Log.info(Tag.NAS_TIMER, "NAS Timer expired: %s", timer);

                if (timer.isMmTimer) {
                    MobilityManagement.receiveTimerExpire(ctx, timer);
                } else {
                    SessionManagement.receiveTimerExpire(ctx, timer);
                }
            } else if (msg instanceof IwUeTestCommand) {
                executeCommand(ctx, ((IwUeTestCommand) msg).cmd);
            } else if (msg instanceof IwConnectionRelease) {
                // TODO
            }
        }
    }
}
