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
import "bootstrap/dist/css/bootstrap.min.css";
import React from "react";
import ReactDOM from "react-dom/client";
import { NavLink, Navigate, RouterProvider, createBrowserRouter } from "react-router-dom";
import { ErrorPage } from "./components/ErrorPage";
import { Home } from "./components/Home";
import { Layout } from "./components/Layout";
import { ListProviders } from "./components/ListProviders";
import { ListVersions } from "./components/ListVersions";
import { BadgeButton } from "./components/BadgeButton";
import { Delete } from "./components/Delete";
import { Install } from "./components/Install";

const LIST_LOCAL = {
    jsonrpc: '2.0',
    method: 'yem.local',
};
const LIST_REMOTE = {
    jsonrpc: '2.0',
    method: 'yem.remote',
};

const router = createBrowserRouter([
    {
        path: '/',
        element: <Layout />,
        errorElement: <Layout><ErrorPage /></Layout>,
        children: [
            {
                path: '/yem/home',
                element: <Home />,
            },
            {
                path: '/yem/providers',
                element: <ListProviders />,
            },
            {
                path: '/yem/local',
                element: <ListVersions
                    request={LIST_LOCAL}
                    title="Local Distributions"
                    description={<>
                        This page lists the <b>installed</b> distributions.
                        It is equivalent to <code>yem list-local</code> command.
                        If you need the available ones, please open <NavLink to="/yem/remote">remote</NavLink> view.
                    </>}
                    actionFactory={(tool, version) => (
                        <BadgeButton
                            icon="#delete"
                            buttonType="danger"
                            onClick={navigate => navigate(`/yem/delete/${tool.provider}/${tool.tool}/${version.identifier}`)}
                        />
                    )}
                />,
            },
            {
                path: '/yem/remote',
                element: <ListVersions
                    request={LIST_REMOTE}
                    title="Remote Distributions"
                    description={<>
                        This page lists the <b>available</b> distributions.
                        It is equivalent to <code>yem list</code> command.
                        If you need the installed ones, please open <NavLink to="/yem/local">local</NavLink> view.
                    </>}
                    actionFactory={(tool, version) => (
                        <BadgeButton
                            icon="#add"
                            buttonType="primary"
                            onClick={navigate => navigate(`/yem/install/${tool.provider}/${tool.tool}/${version.identifier}`)}
                        />
                    )}
                />,
            },
            {
                path: '/yem/delete/:provider/:tool/:version',
                element: <Delete />,
            },
            {
                path: '/yem/install/:provider/:tool/:version',
                element: <Install />,
            },
            {
                path: '/yem/',
                element: <Navigate to='/yem/home' />,
            },
            {
                index: true,
                element: <Navigate to='/yem/home' />,
            },
        ],
    },
]);

ReactDOM
    .createRoot(document.getElementById('app'))
    .render(
        <React.StrictMode>
            <RouterProvider router={router} />
        </React.StrictMode>
    );
