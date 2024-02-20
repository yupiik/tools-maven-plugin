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
import { NavLink } from "react-router-dom";
import { card } from './Home.module.css';

function Item({ title, subtitle, text, link }) {
    const body = (
        <div className="card-body">
            <h5 className="card-title">{title}</h5>
            <h6 className="card-subtitle mb-2 text-body-secondary">{subtitle}</h6>
            <p className="card-text">{text}</p>
        </div>
    );
    return (
        <div className="col-md-4 h-100">
            <div className={`card ${card} m-3 mb-2`}>
                {link.indexOf('://') === 0 ?
                    <NavLink to={link}>{body}</NavLink> :
                    <a href={link} rel="noopener noreferrer" target="_blank">{body}</a>}
            </div>
        </div>
    );
}

export function Home() {
    return (
        <div>
            <h1>Home</h1>
            <div className="row">
                <Item
                    link="/yem/providers"
                    title="Providers"
                    subtitle="Show available providers"
                    text="Providers are responsible of software (de)installation"
                />
                <Item
                    link="/yem/local"
                    title="Local"
                    subtitle="Show installed distributions"
                    text="Enable to check the available distributions"
                />
                <Item
                    link="/yem/remote"
                    title="Remote"
                    subtitle="Show available (remote) distributions"
                    text="Enable to check which distributions can be installed"
                />
                <Item
                    link="https://www.yupiik.io/tools-maven-plugin/yem.html"
                    title="Documentation"
                    subtitle="Official documentation"
                    text="Open official documentation page"
                />
            </div>
        </div>
    );
}
