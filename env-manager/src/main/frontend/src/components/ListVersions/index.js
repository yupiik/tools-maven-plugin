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
import { useJsonRpc } from "../../hook/useJsonRpc";
import { ErrorMessage } from "../ErrorMessage";
import { Select } from "../Select";
import { Skeleton } from "../Skeleton";
import { Table } from "../Table";

export function ListVersions({ request, title, description, actionFactory }) {
    const [loading, error, data] = useJsonRpc(request);

    const [provider, setProvider] = useState('');
    const [tool, setTool] = useState('');
    const [version, setVersion] = useState('');

    const items = useMemo(
        () => ((data || {}).items || [])
            .filter(it => !provider || it.provider === provider)
            .filter(it => !tool || it.tool === tool)
            .filter(it => !version || (it.versions || []).some(v => v.version.indexOf(version) >= 0)),
        [data, provider, tool, version]);
    const availableProviders = useMemo(
        () => Object.keys(((data || {}).items || [])
            .filter(it => !tool || it.tool === tool)
            .reduce((a, i) => {
                a[i.provider] = a;
                return a;
            }, {}))
            .sort((a, b) => a.localeCompare(b))
            .map(it => ({ provider: it })),
        [tool, data]);
    const availableTools = useMemo(
        () => Object.keys(((data || {}).items || [])
            .filter(it => !provider || it.provider === provider)
            .reduce((a, i) => {
                a[i.tool] = a;
                return a;
            }, {}))
            .sort((a, b) => a.localeCompare(b))
            .map(it => ({ tool: it })),
        [provider, data]);

    useEffect(() => {
        if (loading) {
            setProvider('');
            setTool('');
            setVersion('');
        }
    }, [loading, setProvider, setTool, setVersion])

    return (
        <div>
            <h1>{title}</h1>
            <div className="mb-3">
                {description}
            </div>
            <div>
                {loading && <Skeleton />}
                {error && <ErrorMessage error={error} />}
                {!loading && data && (
                    <>
                        <div className="d-flex mb-3">
                            <Select
                                id="provider"
                                items={[{ provider: '' }, ...availableProviders]}
                                attribute="provider"
                                setter={setProvider}
                                className="col-sm-4"
                            />
                            <Select
                                id="tool"
                                items={[{ tool: '' }, ...availableTools]}
                                attribute="tool"
                                setter={setTool}
                                className="col-sm-4"
                            />
                            <div className="form-floating col-sm-4">
                                <input
                                    className="form-control"
                                    id="version"
                                    placeholder="Start typing a version"
                                    value={version}
                                    onChange={({ target: { value } }) => setVersion(value)}
                                />
                                <label htmlFor="version">Filter by version</label>
                            </div>
                        </div>
                        <Table>
                            <thead>
                                <tr>
                                    <th scope="col">Name</th>
                                    <th scope="col">Tool</th>
                                    <th scope="col">Description</th>
                                    <th scope="col">Versions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {items.map(it => (
                                    <tr key={`${it.provider}_${it.tool}`}>
                                        <th scope="row" className="col-sm-1">{it.provider}</th>
                                        <th scope="row" className="col-sm-1">{it.tool}</th>
                                        <td className="col-sm-4">{it.description || '-'}</td>
                                        <td className="col-sm-6">
                                            {(it.versions || [])
                                                .filter(v => !version || v.version.indexOf(version) >= 0)
                                                .map(version => (
                                                    <span key={version.identifier} className="badge rounded-pill text-bg-primary me-1">
                                                        <span className="fw-bold">{version.version}</span>&nbsp;
                                                        <em className="fw-lighter">({version.identifier})</em>
                                                        {actionFactory && <span>{actionFactory(it, version)}</span>}
                                                    </span>
                                                ))}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </Table>
                    </>
                )}
            </div>
        </div >
    );
}
