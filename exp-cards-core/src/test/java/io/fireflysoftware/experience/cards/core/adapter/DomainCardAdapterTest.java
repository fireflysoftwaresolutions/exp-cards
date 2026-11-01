/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.experience.cards.core.adapter;

import io.fireflysoftware.domain.banking.cards.sdk.api.CardBackofficeApi;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardLimitsApi;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import io.fireflysoftware.domain.banking.cards.sdk.model.CardLimitDTO;
import io.fireflysoftware.domain.banking.cards.sdk.model.UpdateCardLimitsCommand;
import io.fireflysoftware.experience.cards.core.limits.CardLimitView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DomainCardAdapterTest {

    @Mock
    private CardsApi cardsApi;
    @Mock
    private CardBackofficeApi cardBackofficeApi;
    @Mock
    private CardLimitsApi cardLimitsApi;

    @Test
    void limitsGoThroughTheCardsDomainSdk() {
        UUID cardId = UUID.randomUUID();
        UUID limitId = UUID.randomUUID();
        DomainCardAdapterImpl adapter = new DomainCardAdapterImpl(cardsApi, cardBackofficeApi, cardLimitsApi);
        when(cardLimitsApi.updateLimits(eq(cardId), eq(limitId), any(UpdateCardLimitsCommand.class), any()))
                .thenReturn(Mono.empty());
        when(cardBackofficeApi.getCardLimits(eq(cardId), any())).thenReturn(Flux.just(
                new CardLimitDTO()
                        .cardLimitId(limitId)
                        .limitType(CardLimitDTO.LimitTypeEnum.DAILY_SPENDING)
                        .limitAmount(new BigDecimal("200.00"))));

        adapter.updateLimits(cardId, limitId, new BigDecimal("200.00"), new BigDecimal("1000.00"),
                new BigDecimal("50.00"), "DAILY_SPENDING").block();
        CardLimitView read = adapter.limits(cardId).block().getFirst();

        assertThat(read.limitType()).isEqualTo("DAILY_SPENDING");
        assertThat(read.amount()).isEqualByComparingTo("200.00");
        verify(cardLimitsApi).updateLimits(eq(cardId), eq(limitId), any(UpdateCardLimitsCommand.class), any());
        verify(cardBackofficeApi).getCardLimits(eq(cardId), any());
    }
}
