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
import { useEffect, useMemo, useState } from "react";
import { NavLink, useNavigate, useParams } from "react-router-dom";
import { useJsonRpc } from "../../hook/useJsonRpc";
import { ErrorMessage } from "../ErrorMessage";
import { Skeleton } from "../Skeleton";

export function Delete() {
    const { provider, tool, version } = useParams();
    const navigate = useNavigate();
    const request = useMemo(
        () => ({
            jsonrpc: '2.0',
            method: 'yem.delete',
            params: {
                provider,
                tool,
                version,
            },
        }),
        [provider, tool, version]);
    const [currentRequest, setCurrentRequest] = useState();
    const [loading, error, data] = useJsonRpc(currentRequest);

    useEffect(
        () => {
            if (data && data.success) {
                navigate('/yem/local');
            }
        },
        [data, navigate]);

    return (
        <div className="alert alert-danger" role="alert">
            <h4 className="alert-heading">You will delete <em>{tool}</em> version <em>{version}</em> with provider {provider}</h4>
            {!loading && error && <ErrorMessage error={error} />}
            {loading && <Skeleton />}
            <div >
                <NavLink type="button" className="btn btn-secondary me-3" to="/yem/local">
                    Cancel
                </NavLink>
                <button type="button" className="btn btn-danger" onClick={() => setCurrentRequest(request)}>
                    Delete from my computer
                </button>
            </div>
        </div>
    );
}
