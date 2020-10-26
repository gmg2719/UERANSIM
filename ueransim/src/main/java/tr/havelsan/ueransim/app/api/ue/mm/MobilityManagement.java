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

package tr.havelsan.ueransim.app.api.ue.mm;

import tr.havelsan.ueransim.app.api.UeNode;
import tr.havelsan.ueransim.app.api.ue.nas.NasTimer;
import tr.havelsan.ueransim.app.api.ue.nas.NasTransport;
import tr.havelsan.ueransim.app.api.ue.sm.SessionManagement;
import tr.havelsan.ueransim.app.enums.EMmState;
import tr.havelsan.ueransim.app.enums.EMmSubState;
import tr.havelsan.ueransim.app.enums.ERmState;
import tr.havelsan.ueransim.app.structs.simctx.UeSimContext;
import tr.havelsan.ueransim.app.structs.testcmd.TestCmd;
import tr.havelsan.ueransim.app.structs.testcmd.TestCmd_Deregistration;
import tr.havelsan.ueransim.app.structs.testcmd.TestCmd_InitialRegistration;
import tr.havelsan.ueransim.app.structs.testcmd.TestCmd_PeriodicRegistration;
import tr.havelsan.ueransim.core.exceptions.NotImplementedException;
import tr.havelsan.ueransim.nas.core.messages.PlainMmMessage;
import tr.havelsan.ueransim.nas.impl.enums.EFollowOnRequest;
import tr.havelsan.ueransim.nas.impl.enums.ERegistrationType;
import tr.havelsan.ueransim.nas.impl.ies.IEDeRegistrationType;
import tr.havelsan.ueransim.nas.impl.messages.*;
import tr.havelsan.ueransim.utils.Tag;
import tr.havelsan.ueransim.utils.console.Log;


public class MobilityManagement {

    public static void sendMm(UeSimContext ctx, PlainMmMessage message) {
        NasTransport.sendNas(ctx, message);
    }

    public static void receiveMm(UeSimContext ctx, PlainMmMessage message) {
        if (message instanceof AuthenticationRequest) {
            MmAuthentication.receiveAuthenticationRequest(ctx, (AuthenticationRequest) message);
        } else if (message instanceof AuthenticationResult) {
            MmAuthentication.receiveAuthenticationResult(ctx, (AuthenticationResult) message);
        } else if (message instanceof AuthenticationResponse) {
            MmAuthentication.receiveAuthenticationResponse(ctx, (AuthenticationResponse) message);
        } else if (message instanceof AuthenticationReject) {
            MmAuthentication.receiveAuthenticationReject(ctx, (AuthenticationReject) message);
        } else if (message instanceof RegistrationReject) {
            MmRegistration.receiveRegistrationReject(ctx, (RegistrationReject) message);
        } else if (message instanceof IdentityRequest) {
            MmIdentity.receiveIdentityRequest(ctx, (IdentityRequest) message);
        } else if (message instanceof RegistrationAccept) {
            MmRegistration.receiveRegistrationAccept(ctx, (RegistrationAccept) message);
        } else if (message instanceof ServiceAccept) {
            MmService.receiveServiceAccept(ctx, (ServiceAccept) message);
        } else if (message instanceof ServiceReject) {
            MmService.receiveServiceReject(ctx, (ServiceReject) message);
        } else if (message instanceof SecurityModeCommand) {
            MmSecurity.receiveSecurityModeCommand(ctx, (SecurityModeCommand) message);
        } else if (message instanceof ConfigurationUpdateCommand) {
            MmConfiguration.receiveConfigurationUpdate(ctx, (ConfigurationUpdateCommand) message);
        } else if (message instanceof DeRegistrationAcceptUeOriginating) {
            MmDeregistration.receiveDeregistrationAccept(ctx, (DeRegistrationAcceptUeOriginating) message);
        } else if (message instanceof DeRegistrationRequestUeTerminated) {
            MmDeregistration.receiveDeregistrationRequest(ctx, (DeRegistrationRequestUeTerminated) message);
        } else if (message instanceof DlNasTransport) {
            SessionManagement.receiveDl(ctx, (DlNasTransport) message);
        } else {
            Log.error(Tag.MESSAGING, "Unhandled message received: %s", message.getClass().getSimpleName());
        }
    }

    public static void receiveTimerExpire(UeSimContext ctx, NasTimer timer) {
        if (timer.timerCode == 3512) {
            if (UeNode.AUTO && ctx.mmCtx.mmState == EMmState.MM_REGISTERED) {
                MmRegistration.sendRegistration(ctx, ERegistrationType.PERIODIC_REGISTRATION_UPDATING, EFollowOnRequest.NO_FOR_PENDING);
            }
        }

        if (timer.timerCode == 3346) {
            if (UeNode.AUTO && ctx.mmCtx.mmSubState == EMmSubState.MM_DEREGISTERED__NORMAL_SERVICE) {
                MmRegistration.sendRegistration(ctx, ERegistrationType.INITIAL_REGISTRATION, EFollowOnRequest.NO_FOR_PENDING);
            }
        }
    }

    public static boolean executeCommand(UeSimContext ctx, TestCmd cmd) {
        if (cmd instanceof TestCmd_InitialRegistration) {
            MmRegistration.sendRegistration(ctx, ERegistrationType.INITIAL_REGISTRATION, ((TestCmd_InitialRegistration) cmd).followOn);
            return true;
        } else if (cmd instanceof TestCmd_PeriodicRegistration) {
            MmRegistration.sendRegistration(ctx, ERegistrationType.PERIODIC_REGISTRATION_UPDATING, ((TestCmd_PeriodicRegistration) cmd).followOn);
            return true;
        } else if (cmd instanceof TestCmd_Deregistration) {
            MmDeregistration.sendDeregistration(ctx, ((TestCmd_Deregistration) cmd).isSwitchOff
                    ? IEDeRegistrationType.ESwitchOff.SWITCH_OFF : IEDeRegistrationType.ESwitchOff.NORMAL_DE_REGISTRATION);
            return true;
        }

        return false;
    }

    public static void switchState(UeSimContext ctx, EMmState state, EMmSubState subState) {
        ctx.mmCtx.mmState = state;
        ctx.mmCtx.mmSubState = subState;

        Log.info(Tag.STATE, "UE switches to state: %s/%s", state, subState);
    }

    public static void switchState(UeSimContext ctx, ERmState state) {
        ctx.mmCtx.rmState = state;
        Log.info(Tag.STATE, "UE switches to state: %s", state);
    }

    public static void cycle(UeSimContext ctx) {
        if (ctx.mmCtx.mmState == EMmState.MM_NULL) {
            switchState(ctx, EMmState.MM_DEREGISTERED, EMmSubState.MM_DEREGISTERED__PLMN_SEARCH);
            return;
        }

        if (ctx.mmCtx.mmSubState == EMmSubState.MM_DEREGISTERED__PLMN_SEARCH) {
            switchState(ctx, EMmState.MM_DEREGISTERED, EMmSubState.MM_DEREGISTERED__NORMAL_SERVICE);
            return;
        }

        if (ctx.mmCtx.mmSubState == EMmSubState.MM_DEREGISTERED__NORMAL_SERVICE) {
            if (UeNode.AUTO && !ctx.ueTimers.t3346.isRunning()) {
                MmRegistration.sendRegistration(ctx, ERegistrationType.INITIAL_REGISTRATION, EFollowOnRequest.NO_FOR_PENDING);
            }
            return;
        }

        if (ctx.mmCtx.mmState == EMmState.MM_REGISTERED_INITIATED) {
            return;
        }

        if (ctx.mmCtx.mmSubState == EMmSubState.MM_REGISTERED__NORMAL_SERVICE) {
            return;
        }

        if (ctx.mmCtx.mmState == EMmState.MM_DEREGISTERED_INITIATED) {
            return;
        }

        if (ctx.mmCtx.mmSubState == EMmSubState.MM_DEREGISTERED__NA) {
            return;
        }

        if (UeNode.AUTO) {
            throw new NotImplementedException("unhandled UE MM state");
        }
    }
}
