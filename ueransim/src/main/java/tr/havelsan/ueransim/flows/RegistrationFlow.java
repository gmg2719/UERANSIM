package tr.havelsan.ueransim.flows;

import threegpp.milenage.Milenage;
import threegpp.milenage.MilenageBufferFactory;
import threegpp.milenage.MilenageResult;
import threegpp.milenage.biginteger.BigIntegerBuffer;
import threegpp.milenage.biginteger.BigIntegerBufferFactory;
import threegpp.milenage.cipher.Ciphers;
import tr.havelsan.ueransim.BaseFlow;
import tr.havelsan.ueransim.IncomingMessage;
import tr.havelsan.ueransim.contexts.SimulationContext;
import tr.havelsan.ueransim.flowinputs.RegistrationInput;
import tr.havelsan.ueransim.nas.core.messages.NasMessage;
import tr.havelsan.ueransim.nas.eap.Eap;
import tr.havelsan.ueransim.nas.eap.EapAkaPrime;
import tr.havelsan.ueransim.nas.impl.enums.EIdentityType;
import tr.havelsan.ueransim.nas.impl.enums.ETypeOfSecurityContext;
import tr.havelsan.ueransim.nas.impl.ies.*;
import tr.havelsan.ueransim.nas.impl.messages.*;
import tr.havelsan.ueransim.ngap.ngap_ies.AMF_UE_NGAP_ID;
import tr.havelsan.ueransim.ngap.ngap_ies.RRCEstablishmentCause;
import tr.havelsan.ueransim.ngap.ngap_pdu_contents.DownlinkNASTransport;
import tr.havelsan.ueransim.ngap.ngap_pdu_contents.InitialContextSetupRequest;
import tr.havelsan.ueransim.ngap2.NgapBuilder;
import tr.havelsan.ueransim.ngap2.NgapCriticality;
import tr.havelsan.ueransim.ngap2.NgapPduDescription;
import tr.havelsan.ueransim.ngap2.NgapProcedure;
import tr.havelsan.ueransim.utils.Color;
import tr.havelsan.ueransim.utils.Console;
import tr.havelsan.ueransim.utils.Utils;
import tr.havelsan.ueransim.utils.octets.Octet;
import tr.havelsan.ueransim.utils.octets.OctetString;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegistrationFlow extends BaseFlow {

    private final RegistrationInput input;
    private final MilenageBufferFactory<BigIntegerBuffer> milenageBufferFactory;
    private long amfUeNgapId; // todo maintain this in simulationContext

    public RegistrationFlow(SimulationContext simContext, RegistrationInput input) {
        super(simContext);
        this.input = input;
        this.milenageBufferFactory = BigIntegerBufferFactory.getInstance();
    }

    @Override
    public State main(IncomingMessage message) {
        var registrationRequest = new RegistrationRequest();
        registrationRequest.registrationType = new IE5gsRegistrationType(
                IE5gsRegistrationType.EFollowOnRequest.NO_FOR_PENDING,
                IE5gsRegistrationType.ERegistrationType.INITIAL_REGISTRATION);
        registrationRequest.nasKeySetIdentifier = new IENasKeySetIdentifier(ETypeOfSecurityContext.NATIVE_SECURITY_CONTEXT, input.ngKSI);
        registrationRequest.requestedNSSAI = new IENssai(input.requestNssai);
        registrationRequest.mobileIdentity = input.mobileIdentity;

        sendNgap(new NgapBuilder()
                .withDescription(NgapPduDescription.INITIATING_MESSAGE)
                .withProcedure(NgapProcedure.InitialUEMessage, NgapCriticality.IGNORE)
                .addRanUeNgapId(input.ranUeNgapId, NgapCriticality.REJECT)
                .addNasPdu(registrationRequest, NgapCriticality.REJECT)
                .addUserLocationInformationNR(input.userLocationInformationNr, NgapCriticality.REJECT)
                .addProtocolIE(new RRCEstablishmentCause(input.rrcEstablishmentCause), NgapCriticality.IGNORE)
                .build());

        return this::waitAmfMessages;
    }

    private State waitAmfMessages(IncomingMessage message) {
        var initialContextSetupRequest = message.getNgapMessage(InitialContextSetupRequest.class);
        if (initialContextSetupRequest != null) {
            return handleInitialContextSetup();
        }

        var downlinkNasTransport = message.getNgapMessage(DownlinkNASTransport.class);
        if (downlinkNasTransport != null) {
            for (var item : downlinkNasTransport.protocolIEs.valueList) {
                var protocolIE = (DownlinkNASTransport.ProtocolIEs.SEQUENCE) item;
                if (protocolIE.value.getDecodedValue() instanceof AMF_UE_NGAP_ID) {
                    var amfUeNgapId = (AMF_UE_NGAP_ID) protocolIE.value.getDecodedValue();
                    this.amfUeNgapId = amfUeNgapId.value;
                }
            }

            var nasMessage = message.getNasMessage(NasMessage.class);
            if (nasMessage != null) {
                return handleNasMessage(nasMessage);
            }
        }

        Console.println(Color.YELLOW, "unhandled ngap message. message ignored");
        return this::waitAmfMessages;
    }

    private State handleNasMessage(NasMessage message) {
        if (message instanceof AuthenticationRequest) {
            return handleAuthenticationRequest((AuthenticationRequest) message);
        } else if (message instanceof AuthenticationResult) {
            Console.println(Color.BLUE, "Authentication result received");
            return this::waitAmfMessages;
        } else if (message instanceof RegistrationReject) {
            Console.println(Color.RED, "RegistrationReject result received.");
            return closeConnection();
        } else if (message instanceof IdentityRequest) {
            return handleIdentityRequest((IdentityRequest) message);
        } else if (message instanceof RegistrationAccept) {
            return handleRegistrationAccept((RegistrationAccept) message);
        } else {
            Console.println(
                    Color.YELLOW,
                    "This message type was not implemented yet: %s",
                    message.getClass().getSimpleName());
            Console.println(Color.YELLOW, "Ignoring message");
            return this::waitAmfMessages;
        }
    }

    private State handleInitialContextSetup() {
        sendNgap(new NgapBuilder()
                .withDescription(NgapPduDescription.SUCCESSFUL_OUTCOME)
                .withProcedure(NgapProcedure.InitialContextSetupResponse, NgapCriticality.REJECT)
                .addRanUeNgapId(input.ranUeNgapId, NgapCriticality.IGNORE)
                .addAmfUeNgapId(amfUeNgapId, NgapCriticality.IGNORE)
                .build());

        sendNgap(new NgapBuilder()
                .withDescription(NgapPduDescription.INITIATING_MESSAGE)
                .withProcedure(NgapProcedure.UplinkNASTransport, NgapCriticality.IGNORE)
                .addRanUeNgapId(input.ranUeNgapId, NgapCriticality.REJECT)
                .addAmfUeNgapId(amfUeNgapId, NgapCriticality.REJECT)
                .addNasPdu(new RegistrationComplete(), NgapCriticality.REJECT)
                .addUserLocationInformationNR(input.userLocationInformationNr, NgapCriticality.IGNORE)
                .build());

        Console.println(Color.GREEN_BOLD, "Registration successfully completed.");
        return abortReceiver();
    }

    private State handleRegistrationAccept(RegistrationAccept message) {
        sendNgap(new NgapBuilder()
                .withDescription(NgapPduDescription.INITIATING_MESSAGE)
                .withProcedure(NgapProcedure.UplinkNASTransport, NgapCriticality.IGNORE)
                .addRanUeNgapId(input.ranUeNgapId, NgapCriticality.REJECT)
                .addAmfUeNgapId(amfUeNgapId, NgapCriticality.REJECT)
                .addNasPdu(new RegistrationComplete(), NgapCriticality.REJECT)
                .addUserLocationInformationNR(input.userLocationInformationNr, NgapCriticality.IGNORE)
                .build());

        return flowComplete();
    }

    private State handleAuthenticationRequest(AuthenticationRequest message) {
        Console.printDiv();
        Console.println(Color.BLUE, "AuthenticationRequest is handling.");

        OctetString mac;
        byte[] res;
        Octet id;

        // Handle request
        {
            var akaPrime = (EapAkaPrime) message.eapMessage.eap;
            var rand = akaPrime.attributes.get(EapAkaPrime.EAttributeType.AT_RAND);
            rand = rand.substring(2); // reserved octets

            mac = akaPrime.attributes.get(EapAkaPrime.EAttributeType.AT_MAC);
            id = akaPrime.id;

            final String OP = input.eapAkaInput.OP;
            final String SQN = input.eapAkaInput.SQN;
            final String AMF = input.eapAkaInput.AMF;
            final String KEY = input.eapAkaInput.KEY;

            byte[] op = Utils.hexStringToByteArray(OP);
            byte[] sqn = Utils.hexStringToByteArray(SQN);
            byte[] amf = Utils.hexStringToByteArray(AMF);

            var cipher = Ciphers.createRijndaelCipher(Utils.hexStringToByteArray(KEY));

            byte[] opc = Milenage.calculateOPc(op, cipher, milenageBufferFactory);

            Milenage<BigIntegerBuffer> milenage = new Milenage<>(opc, cipher, milenageBufferFactory);
            ExecutorService executorService = Executors.newCachedThreadPool();

            Map<MilenageResult, byte[]> result;
            try {
                result = milenage.calculateAll(rand.substring(0).toByteArray(), sqn, amf, executorService);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            executorService.shutdown();
            res = result.get(MilenageResult.RES);

            int padding = (res.length) % 4;
            byte[] paddedRes = new byte[res.length + padding + 2];
            System.arraycopy(res, 0, paddedRes, padding + 2, res.length);
            int resBitLength = (paddedRes.length - 2) * 8;
            paddedRes[0] = (byte) ((resBitLength >> 8) & 0xFF);
            paddedRes[1] = (byte) (resBitLength & 0xFF);
            res = paddedRes;
        }

        // Send response
        {
            var response = new AuthenticationResponse();
            response.authenticationResponseParameter =
                    new IEAuthenticationResponseParameter(input.authenticationResponseParameter);

            var akaPrime = new EapAkaPrime(Eap.ECode.RESPONSE, id);
            akaPrime.subType = EapAkaPrime.ESubType.AKA_CHALLENGE;
            akaPrime.attributes = new LinkedHashMap<>();
            akaPrime.attributes.put(EapAkaPrime.EAttributeType.AT_RES, new OctetString(res));
            akaPrime.attributes.put(EapAkaPrime.EAttributeType.AT_MAC, mac);
            akaPrime.attributes.put(EapAkaPrime.EAttributeType.AT_KDF, new OctetString("0001"));
            response.eapMessage = new IEEapMessage(akaPrime);

            sendNgap(new NgapBuilder()
                    .withDescription(NgapPduDescription.INITIATING_MESSAGE)
                    .withProcedure(NgapProcedure.UplinkNASTransport, NgapCriticality.IGNORE)
                    .addRanUeNgapId(input.ranUeNgapId, NgapCriticality.REJECT)
                    .addAmfUeNgapId(amfUeNgapId, NgapCriticality.REJECT)
                    .addNasPdu(response, NgapCriticality.REJECT)
                    .addUserLocationInformationNR(input.userLocationInformationNr, NgapCriticality.IGNORE)
                    .build());
        }

        return this::waitAmfMessages;
    }

    private State handleIdentityRequest(IdentityRequest message) {
        IdentityResponse response = new IdentityResponse();

        if (message.identityType.value.equals(EIdentityType.IMEI)) {
            response.mobileIdentity = new IEImeiMobileIdentity(input.imei);
        } else if (message.identityType.value.equals(EIdentityType.SUCI)) {
            if (!(input.mobileIdentity instanceof IESuciMobileIdentity)) {
                Console.println(Color.RED, "Identity request for %s is not provided in registration.yaml",
                        message.identityType.value.name());
                return this::waitAmfMessages;
            }
            response.mobileIdentity = input.mobileIdentity;
        } else {
            Console.println(Color.RED, "Identity request for %s is not implemented yet",
                    message.identityType.value.name());
            return this::waitAmfMessages;
        }

        sendNgap(new NgapBuilder()
                .withDescription(NgapPduDescription.INITIATING_MESSAGE)
                .withProcedure(NgapProcedure.UplinkNASTransport, NgapCriticality.IGNORE)
                .addRanUeNgapId(input.ranUeNgapId, NgapCriticality.REJECT)
                .addAmfUeNgapId(amfUeNgapId, NgapCriticality.REJECT)
                .addNasPdu(response, NgapCriticality.REJECT)
                .addUserLocationInformationNR(input.userLocationInformationNr, NgapCriticality.IGNORE)
                .build());

        return this::waitAmfMessages;
    }
}
