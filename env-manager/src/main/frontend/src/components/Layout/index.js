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
import { NavLink, Outlet } from "react-router-dom";
import { content, main } from "./Layout.module.css";

export function Layout({ children }) {
    return (
        <>
            <svg xmlns="http://www.w3.org/2000/svg" style={{ display: 'none' }}>
                <symbol id="yupiik" viewBox="0 0 88 113">
                    <title>Yupiik</title>
                    <svg version="1.1" width="88" height="113" xmlns="http://www.w3.org/2000/svg">
                        <defs id="defs854"><style id="style852"></style></defs>
                        <g id="g906" transform="translate(-86.579 -4.036)">
                            <path d="m171.25 71.78-36.3-62.87A5.833 5.833 0 0 0 129.91 6c-2.07 0-4 1.11-5.04 2.91L88.56 71.79a5.829 5.829 0 0 0 2.73 8.25l29 12.56-20.45 8.85 5.81-10.12-7.06-3.05-8.12 14.13a5.81 5.81 0 0 0 .7 6.79 5.806 5.806 0 0 0 4.34 1.95c.78 0 1.57-.16 2.32-.48l28.26-12.24v13.77a3.82 3.82 0 1 0 7.64 0V98.43l28.26 12.23a5.821 5.821 0 0 0 7.36-8.25l-10.49-18.17 9.67-4.19a5.831 5.831 0 0 0 3.21-3.51c.52-1.58.34-3.3-.49-4.74zm-11.28 29.66-20.45-8.85 12.27-5.31 8.18 14.17zM129.9 88.42l-18.04-7.81 18.04-31.25 15.44 26.88 7.06-3.05-17.46-30.38a5.833 5.833 0 0 0-5.04-2.91c-2.07 0-4 1.11-5.04 2.91L104.8 77.55l-8.6-3.73 33.7-58.37 33.7 58.37-33.7 14.59z" style={{ fill: '#00b2ee' }} />
                        </g>
                    </svg>
                </symbol>
                <symbol id="delete" viewBox="0 0 20 20">
                    <title>delete</title>
                    <svg fill="#f00" width="800px" height="800px" xmlns="http://www.w3.org/2000/svg">
                        <path d="M19.059 10.898l-3.171-7.927A1.543 1.543 0 0 0 14.454 2H5.546c-.632 0-1.2.384-1.434.971L.941 10.898a4.25 4.25 0 0 0-.246 2.272l.59 3.539A1.544 1.544 0 0 0 2.808 18h14.383c.755 0 1.399-.546 1.523-1.291l.59-3.539a4.22 4.22 0 0 0-.245-2.272zM5.52 4.786l1.639-1.132 2.868 2.011 2.868-2.011 1.639 1.132-2.869 2.033 2.928 2.06-1.639 1.171-2.927-2.076L7.1 10.05 5.461 8.879l2.928-2.06L5.52 4.786zm11.439 10.459a.902.902 0 0 1-.891.755H3.932a.902.902 0 0 1-.891-.755l-.365-2.193A.902.902 0 0 1 3.567 12h12.867c.558 0 .983.501.891 1.052l-.366 2.193z" />
                    </svg>
                </symbol>
                <symbol id="add" viewBox="0 0 24 24">
                    <title>delete</title>
                    <svg width="800px" height="800px" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M12.75 9C12.75 8.58579 12.4142 8.25 12 8.25C11.5858 8.25 11.25 8.58579 11.25 9L11.25 11.25H9C8.58579 11.25 8.25 11.5858 8.25 12C8.25 12.4142 8.58579 12.75 9 12.75H11.25V15C11.25 15.4142 11.5858 15.75 12 15.75C12.4142 15.75 12.75 15.4142 12.75 15L12.75 12.75H15C15.4142 12.75 15.75 12.4142 15.75 12C15.75 11.5858 15.4142 11.25 15 11.25H12.75V9Z" fill="#1C274C" />
                        <path fillRule="evenodd" clipRule="evenodd" d="M12.0574 1.25H11.9426C9.63424 1.24999 7.82519 1.24998 6.41371 1.43975C4.96897 1.63399 3.82895 2.03933 2.93414 2.93414C2.03933 3.82895 1.63399 4.96897 1.43975 6.41371C1.24998 7.82519 1.24999 9.63422 1.25 11.9426V12.0574C1.24999 14.3658 1.24998 16.1748 1.43975 17.5863C1.63399 19.031 2.03933 20.1711 2.93414 21.0659C3.82895 21.9607 4.96897 22.366 6.41371 22.5603C7.82519 22.75 9.63423 22.75 11.9426 22.75H12.0574C14.3658 22.75 16.1748 22.75 17.5863 22.5603C19.031 22.366 20.1711 21.9607 21.0659 21.0659C21.9607 20.1711 22.366 19.031 22.5603 17.5863C22.75 16.1748 22.75 14.3658 22.75 12.0574V11.9426C22.75 9.63423 22.75 7.82519 22.5603 6.41371C22.366 4.96897 21.9607 3.82895 21.0659 2.93414C20.1711 2.03933 19.031 1.63399 17.5863 1.43975C16.1748 1.24998 14.3658 1.24999 12.0574 1.25ZM3.9948 3.9948C4.56445 3.42514 5.33517 3.09825 6.61358 2.92637C7.91356 2.75159 9.62177 2.75 12 2.75C14.3782 2.75 16.0864 2.75159 17.3864 2.92637C18.6648 3.09825 19.4355 3.42514 20.0052 3.9948C20.5749 4.56445 20.9018 5.33517 21.0736 6.61358C21.2484 7.91356 21.25 9.62177 21.25 12C21.25 14.3782 21.2484 16.0864 21.0736 17.3864C20.9018 18.6648 20.5749 19.4355 20.0052 20.0052C19.4355 20.5749 18.6648 20.9018 17.3864 21.0736C16.0864 21.2484 14.3782 21.25 12 21.25C9.62177 21.25 7.91356 21.2484 6.61358 21.0736C5.33517 20.9018 4.56445 20.5749 3.9948 20.0052C3.42514 19.4355 3.09825 18.6648 2.92637 17.3864C2.75159 16.0864 2.75 14.3782 2.75 12C2.75 9.62177 2.75159 7.91356 2.92637 6.61358C3.09825 5.33517 3.42514 4.56445 3.9948 3.9948Z" fill="#1C274C" />
                    </svg>
                </symbol>
            </svg>
            <main className={main}>
                <div className="d-flex flex-column flex-shrink-0 p-3 text-white bg-dark" style={{ width: '280px' }}>
                    <a href="/" className="d-flex align-items-center mb-3 mb-md-0 me-md-auto text-white text-decoration-none">
                        <svg className="bi me-2" width="40" height="32">
                            <use xlinkHref="#yupiik"></use>
                        </svg>
                        <span className="fs-4">YEM</span>
                    </a>
                    <hr />
                    <ul className="nav nav-pills flex-column mb-auto">
                        <li className="nav-item">
                            <NavLink to="/yem/home" className="nav-link text-white">
                                Home
                            </NavLink>
                        </li>
                        <li>
                            <NavLink to="/yem/providers" className="nav-link text-white">
                                Providers
                            </NavLink>
                        </li>
                        <li>
                            <NavLink to="/yem/local" className="nav-link text-white">
                                Local
                            </NavLink>
                        </li>
                        <li>
                            <NavLink to="/yem/remote" className="nav-link text-white">
                                Available (Remote)
                            </NavLink>
                        </li>
                    </ul>
                </div>

                <div className={content}>
                    {children || <Outlet />}
                </div>
            </main>
        </>
    );
}
