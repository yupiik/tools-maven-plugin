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
import { Fragment, useState } from "react";
import { useJsonRpc } from "../../hook/useJsonRpc";
import { ErrorMessage } from "../ErrorMessage";
import { Skeleton } from "../Skeleton";
import { Table } from "../Table";
import { Select } from "../Select";

const REQUEST = {
    jsonrpc: '2.0',
    method: 'yem.providers',
};

function Configuration({ configuration }) {
    return (
        <dl className="row" style={{ margin: '0' }}>
            {Object.entries(configuration).map(([key, value]) => (
                <Fragment key={key}>
                    <dt className="col-sm-1">{key}</dt>
                    <dd className="col-sm-11">
                        <p style={{ marginBottom: '0' }}>{value.value || '-'}</p>
                        <p style={{ marginBottom: '0' }}>{value.documentation || ''}</p>
                    </dd>
                </Fragment>
            ))}
        </dl>
    );
}

export function ListProviders() {
    const [loading, error, data] = useJsonRpc(REQUEST);
    const [provider, setProvider] = useState();

    return (
        <div>
            <h1>Providers Configuration</h1>
            <div className="mb-3">
                This page lists the configuration you can do.
                Most are injectable in <code>~/.yupiik/yem/rc</code> file to keep it by default.
            </div>
            <div>
                {loading && <Skeleton />}
                {error && <ErrorMessage error={error} />}
                {data && (
                    <>
                        <div>
                            <Select
                                id="provider"
                                items={[{ name: '' }, ...(data.items || [])]}
                                attribute="name"
                                setter={setProvider}
                            />
                        </div>
                        <Table>
                            <thead>
                                <tr>
                                    <th scope="col">Name</th>
                                    <th scope="col">Class</th>
                                    <th scope="col">Configuration</th>
                                </tr>
                            </thead>
                            <tbody>
                                {(data.items || [])
                                    .filter(it => !provider || provider === it.name)
                                    .map(it => (
                                        <tr key={it.name}>
                                            <th scope="row">{it.name}</th>
                                            <td>{it.className}</td>
                                            <td><Configuration configuration={it.configuration || {}} /></td>
                                        </tr>
                                    ))}
                            </tbody>
                        </Table>
                    </>
                )}
            </div>
        </div>
    );
}
