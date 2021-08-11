/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.rpc.protocol.tri;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture2;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.triple.TripleWrapper;

import com.google.protobuf.Any;
import com.google.rpc.DebugInfo;
import com.google.rpc.Status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class UnaryClientStream extends AbstractClientStream implements Stream {


    protected UnaryClientStream(URL url, Executor executor) {
        super(url, executor);
    }

    @Override
    protected StreamObserver<Object> createStreamObserver() {
        return new ClientStreamObserver();
    }

    @Override
    protected TransportObserver createTransportObserver() {
        return new UnaryClientTransportObserver();
    }

    private class UnaryClientTransportObserver extends UnaryTransportObserver implements TransportObserver {

        @Override
        public void doOnComplete(OperationHandler handler) {
            execute(() -> {
                try {
                    final Object resp = deserializeResponse(getData());
                    Response response = new Response(getRequest().getId(), TripleConstant.TRI_VERSION);
                    final AppResponse result = new AppResponse(resp);
                    result.setObjectAttachments(parseMetadataToMap(getTrailers()));
                    response.setResult(result);
                    DefaultFuture2.received(getConnection(), response);
                } catch (Exception e) {
                    final GrpcStatus status = GrpcStatus.fromCode(GrpcStatus.Code.INTERNAL)
                            .withCause(e)
                            .withDescription("Failed to deserialize response");
                    onError(status);
                }
            });
        }

        @Override
        protected void onError(GrpcStatus status) {
            Response response = new Response(getRequest().getId(), TripleConstant.TRI_VERSION);
            response.setErrorMessage(status.description);
            final AppResponse result = new AppResponse();
            result.setException(getThrowable(this.getTrailers()));
            result.setObjectAttachments(UnaryClientStream.this.parseMetadataToMap(this.getTrailers()));
            response.setResult(result);
            if (!result.hasException()) {
                final byte code = GrpcStatus.toDubboStatus(status.code);
                response.setStatus(code);
            }
            DefaultFuture2.received(getConnection(), response);
        }

        private Throwable getThrowable(Metadata metadata) {
            // first get throwable from exception tw bin
            try {
                if (metadata.contains(TripleConstant.EXCEPTION_TW_BIN)) {
                    final CharSequence raw = metadata.get(TripleConstant.EXCEPTION_TW_BIN);
                    byte[] exceptionTwBin = TripleUtil.decodeASCIIByte(raw);
                    ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                    try {
                        TripleWrapper.TripleExceptionWrapper wrapper = TripleUtil.unpack(exceptionTwBin,
                                TripleWrapper.TripleExceptionWrapper.class);
                        Throwable throwable = TripleUtil.unWrapException(getUrl(), wrapper, getSerializeType(),
                                getMultipleSerialization());
                        if (throwable != null) {
                            return throwable;
                        }
                    } finally {
                        ClassLoadUtil.switchContextLoader(tccl);
                    }
                    // avoid subsequent parse header problems
                    metadata.remove(TripleConstant.EXCEPTION_TW_BIN);
                }
            } catch (Throwable t) {
                LOGGER.warn(String.format("Decode exception instance from triple trailers:%s failed", metadata), t);
            }
            // second get status detail
            if (metadata.contains(TripleConstant.STATUS_DETAIL_KEY)) {
                final CharSequence raw = metadata.get(TripleConstant.STATUS_DETAIL_KEY);
                byte[] statusDetailBin = TripleUtil.decodeASCIIByte(raw);
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                try {
                    final Status statusDetail = TripleUtil.unpack(statusDetailBin, Status.class);
                    List<Any> detailList = statusDetail.getDetailsList();
                    Map<Class<?>, Object> classObjectMap = TripleUtil.tranFromStatusDetails(detailList);

                    // get common exception from DebugInfo
                    DebugInfo debugInfo = (DebugInfo) classObjectMap.get(DebugInfo.class);
                    if (debugInfo == null) {
                        return new TripleRpcException(statusDetail.getCode(),
                                statusDetail.getMessage(), metadata);
                    }
                    String msg = ExceptionUtils.getStackFrameString(debugInfo.getStackEntriesList());
                    return new TripleRpcException(statusDetail.getCode(), msg, metadata);
                } finally {
                    ClassLoadUtil.switchContextLoader(tccl);
                }
            }
            return null;
        }
    }
}