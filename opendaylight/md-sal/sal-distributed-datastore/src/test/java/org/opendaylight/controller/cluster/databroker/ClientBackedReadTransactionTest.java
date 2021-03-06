/*
 * Copyright (c) 2017 Pantheon Technologies s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.databroker;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.opendaylight.controller.cluster.access.client.ClientActorContext;
import org.opendaylight.controller.cluster.databroker.actors.dds.ClientSnapshot;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;

public class ClientBackedReadTransactionTest extends ClientBackedTransactionTest<ClientBackedReadTransaction> {
    private ClientBackedReadTransaction object;

    @Mock
    private NormalizedNode data;
    @Mock
    private ClientActorContext clientContext;
    @Mock
    private ClientSnapshot delegate;

    @Override
    ClientBackedReadTransaction object() throws Exception {
        return object;
    }

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Mockito.doReturn(CLIENT_ID).when(clientContext).getIdentifier();
        Mockito.doReturn(TRANSACTION_ID).when(delegate).getIdentifier();

        Mockito.doReturn(Futures.immediateCheckedFuture(Boolean.TRUE)).when(delegate)
                .exists(YangInstanceIdentifier.EMPTY);
        Mockito.doReturn(Futures.immediateCheckedFuture(Optional.of(data))).when(delegate)
                .read(YangInstanceIdentifier.EMPTY);

        object = new ClientBackedReadTransaction(delegate, null);
    }

    @Test
    public void testRead() throws Exception {
        final CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> result = object().read(
                YangInstanceIdentifier.EMPTY);
        final Optional<NormalizedNode<?, ?>> resultData = result.get();
        Assert.assertTrue(resultData.isPresent());
        Assert.assertEquals(data, resultData.get());
    }

    @Test
    public void testExists() throws Exception {
        final CheckedFuture<Boolean, ReadFailedException> result = object().exists(YangInstanceIdentifier.EMPTY);
        Assert.assertTrue(result.get());
    }
}