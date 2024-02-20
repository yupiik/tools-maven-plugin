/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import { useCallback, useEffect, useState } from 'react';

export const DEFAULT_JSONRPC_HEADERS = {
    'accept': 'application/json;charset=utf-8',
    'content-type': 'application/json;charset=utf-8',
};

export const useJsonRpc = payload => {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState();
    const [data, setData] = useState();
    const resetCallback = useCallback(() => { // reset callback
        setData(undefined);
        setError(undefined);
        setLoading(false);
    }, [setData, setError, setLoading]);

    useEffect(() => {
        if (!payload) {
            return;
        }

        async function doLoad(signal) {
            setLoading(true);
            try {
                const response = await fetch('/yem/jsonrpc', {
                    method: 'POST',
                    headers: DEFAULT_JSONRPC_HEADERS,
                    body: JSON.stringify(typeof payload === 'function' ? payload() : payload),
                    signal,
                });

                if (response.status !== 200) {
                    setError({ message: `HTTP error, status: ${response.status}` });
                    return true;
                }

                const json = await response.json();
                if (Array.isArray(json)) {
                    const errors = json.filter(it => it.error).map(it => it.error);
                    if (errors.length > 0) {
                        setError({ errors, message: errors.map(e => e.message || e.code).join(',\n') });
                    } else {
                        setData(json.map(it => it.result));
                        setError(undefined);
                    }
                }

                if (json.error) {
                    setError(json.error);
                } else {
                    setData(json.result);
                    setError(undefined);
                }
            } catch (e) {
                setError({ message: e.message || JSON.stringify(e) });
            } finally {
                setLoading(false);
            }
            return true;
        };

        const aborter = new AbortController();
        let done = false;
        doLoad(aborter.signal)
            .then(() => { done = true })
            .catch(() => { done = true });
        return () => {
            if (!done) {console.log('abort')
                aborter.abort();
            }
        };
    }, [payload]);

    return [loading, error, data, resetCallback];
};
