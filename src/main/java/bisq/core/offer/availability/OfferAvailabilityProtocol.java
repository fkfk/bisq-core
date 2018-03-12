/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.offer.availability;

import bisq.core.offer.Offer;
import bisq.core.offer.availability.tasks.ProcessOfferAvailabilityResponse;
import bisq.core.offer.availability.tasks.SendOfferAvailabilityRequest;
import bisq.core.offer.messages.OfferAvailabilityResponse;
import bisq.core.offer.messages.OfferMessage;
import bisq.core.util.Validator;

import bisq.network.p2p.DecryptedDirectMessageListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.taskrunner.TaskRunner;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OfferAvailabilityProtocol {
    private static final long TIMEOUT = 90;

    private final OfferAvailabilityModel model;
    private final ResultHandler resultHandler;
    private final ErrorMessageHandler errorMessageHandler;
    private final DecryptedDirectMessageListener decryptedDirectMessageListener;

    private TaskRunner<OfferAvailabilityModel> taskRunner;
    private Timer timeoutTimer;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public OfferAvailabilityProtocol(OfferAvailabilityModel model, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        this.model = model;
        this.resultHandler = resultHandler;
        this.errorMessageHandler = errorMessageHandler;

        decryptedDirectMessageListener = (decryptedMessageWithPubKey, peersNodeAddress) -> {
            NetworkEnvelope networkEnvelop = decryptedMessageWithPubKey.getNetworkEnvelope();
            if (networkEnvelop instanceof OfferMessage) {
                OfferMessage offerMessage = (OfferMessage) networkEnvelop;
                Validator.nonEmptyStringOf(offerMessage.offerId);
                if (networkEnvelop instanceof OfferAvailabilityResponse
                        && model.offer.getId().equals(offerMessage.offerId)) {
                    log.trace("handle OfferAvailabilityResponse = " + networkEnvelop.getClass().getSimpleName() + " from " + peersNodeAddress);
                    handle((OfferAvailabilityResponse) networkEnvelop);
                }
            }
        };
    }

    private void cleanup() {
        stopTimeout();
        model.p2PService.removeDecryptedDirectMessageListener(decryptedDirectMessageListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendOfferAvailabilityRequest() {
        // reset
        model.offer.setState(Offer.State.UNKNOWN);

        model.p2PService.addDecryptedDirectMessageListener(decryptedDirectMessageListener);
        model.setPeerNodeAddress(model.offer.getMakerNodeAddress());

        taskRunner = new TaskRunner<>(model,
                () -> log.debug("sequence at sendOfferAvailabilityRequest completed"),
                (errorMessage) -> {
                    log.error(errorMessage);
                    stopTimeout();
                    errorMessageHandler.handleErrorMessage(errorMessage);
                }
        );
        taskRunner.addTasks(SendOfferAvailabilityRequest.class);
        startTimeout();
        taskRunner.run();
    }

    public void cancel() {
        taskRunner.cancel();
        cleanup();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(OfferAvailabilityResponse message) {
        stopTimeout();
        startTimeout();
        model.setMessage(message);

        taskRunner = new TaskRunner<>(model,
                () -> {
                    log.debug("sequence at handle OfferAvailabilityResponse completed");
                    stopTimeout();
                    resultHandler.handleResult();
                },
                (errorMessage) -> {
                    log.error(errorMessage);
                    stopTimeout();
                    errorMessageHandler.handleErrorMessage(errorMessage);
                }
        );
        taskRunner.addTasks(ProcessOfferAvailabilityResponse.class);
        taskRunner.run();
    }

    private void startTimeout() {
        if (timeoutTimer == null) {
            timeoutTimer = UserThread.runAfter(() -> {
                log.debug("Timeout reached at " + this);
                model.offer.setState(Offer.State.MAKER_OFFLINE);
                errorMessageHandler.handleErrorMessage("Timeout reached: Peer has not responded.");
            }, TIMEOUT);
        } else {
            log.warn("timeoutTimer already created. That must not happen.");
        }
    }

    private void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }
}