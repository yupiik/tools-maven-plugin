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
const esbuild = require('esbuild');
const fs = require('fs');
const path = require('path');
const http = require('node:http');

const dev = process.env.NODE_ENV === 'dev';

const outDir = '../../../target/classes/META-INF/resources/yem/';
const projectVersion = process.env.PROJECT_VERSION || 'dev';
const indexHtmlVersion = projectVersion.endsWith('-SNAPSHOT') ?
    process.env.BUILD_MARKER.replace(':', '-').replace('T', '').replace('Z', '') :
    projectVersion;

const copyResources = (options = []) => {
    options.forEach(({ from, to }) => {
        if (!fs.existsSync(from)) {
            throw new Error(`No such file '${from}'`);
        }

        const base = path.dirname(to);
        if (!fs.existsSync(base)) {
            fs.mkdirSync(base, { recursive: true });
        }
        let content = fs.readFileSync(from).toString('utf8')
            .replace(new RegExp('{{static.marker}}', 'g'), indexHtmlVersion);
        fs.writeFileSync(to, content);
    })
};

const onRebuild = () => copyResources([{ from: './index.html', to: outDir + 'index.html' }]);

const conf = {
    loader: {
        '.js': 'jsx',
        '.module.css': 'local-css',
        '.css': 'css',
        '.png': 'dataurl',
        '.svg': 'dataurl',
    },
    entryPoints: {
        [`js/app.${indexHtmlVersion}`]: './src/index.js',
    },
    bundle: true,
    metafile: dev,
    minify: !dev,
    sourcemap: dev,
    legalComments: 'none',
    logLevel: 'info',
    target: ['chrome58', 'firefox57', 'safari11'],
    outfile: outDir + 'js/app.js',
    jsx: 'automatic',
};

const jsonRpcMocks = ({ method }) => {
    switch (method) {
        case 'yem.providers': return {
            jsonrpc: '2.0',
            result: {
                items: [
                    {
                        name: 'zulu',
                        className: 'ZuluCdnClient',
                        configuration: {
                            enabled: {
                                value: 'true',
                                documentation: 'Is zulu provider enabled',
                            },
                            preferJre: {
                                value: 'false',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                            preferApi: {
                                value: 'true',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                            base: {
                                value: 'http://foo.bar',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                            apiBase: {
                                value: 'http://foo.bar',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                            local: {
                                value: '~/.yupiik/yem/zulu',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                        },
                    },
                    {
                        name: 'sdkman',
                        className: 'SdkManClient',
                        configuration: {
                            enabled: {
                                value: 'true',
                                documentation: 'Is sdkman provider enabled',
                            },
                            base: {
                                value: 'http://foo.bar',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                            local: {
                                value: '~/.yupiik/yem/zulu',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                            platform: {
                                value: 'linux_x64',
                                documentation: 'xxxx xxxxx xxxxx xxxx xxxx xxxx xxxx xxx',
                            },
                        },
                    },
                ],
            },
        };
        case 'yem.remote':
        case 'yem.local': return { "result": { "items": [{ "versions": [{ "version": "7.5.1", "identifier": "7.5.1" }, { "version": "8.1", "identifier": "8.1" }], "description": "", "tool": "gradle", "provider": "sdkman" }, { "versions": [{ "version": "3.0.8", "identifier": "3.0.8" }], "description": "", "tool": "groovy", "provider": "sdkman" }, { "versions": [{ "version": "8.0.312-zulu", "identifier": "8.0.312-zulu" }, { "version": "11.0.14.fx-zulu", "identifier": "11.0.14.fx-zulu" }, { "version": "17.0.9-zulu", "identifier": "17.0.9-zulu" }, { "version": "21-zulu", "identifier": "21-zulu" }], "description": "", "tool": "java", "provider": "sdkman" }, { "versions": [{ "version": "21.0.1", "identifier": "21.30.15-ca-fx-jdk21.0.1" }], "description": "Java JRE or JDK downloaded from Azul CDN.", "tool": "java", "provider": "zulu" }, { "versions": [{ "version": "0.114.0", "identifier": "0.114.0" }], "description": "", "tool": "jbang", "provider": "sdkman" }, { "versions": [{ "version": "3.6.3", "identifier": "3.6.3" }, { "version": "3.8.6", "identifier": "3.8.6" }, { "version": "3.9.2", "identifier": "3.9.2" }, { "version": "4.0.0-alpha-10", "identifier": "4.0.0-alpha-10" }, { "version": "4.0.0-alpha-12", "identifier": "4.0.0-alpha-12" }], "description": "", "tool": "maven", "provider": "sdkman" }, { "versions": [{ "version": "1.0-m7", "identifier": "1.0-m7" }, { "version": "1.0-m6-m40", "identifier": "1.0-m6-m40" }], "description": "", "tool": "mvnd", "provider": "sdkman" }], "total": 7 }, "jsonrpc": "2.0" };
        case 'yem.install':
        case 'yem.delete': return {
            jsonrpc: '2.0',
            result: {
                success: true,
            },
        };
        default: return {
            jsonrpc: '2.0',
            error: {
                code: -32601,
                message: 'unknown method',
            },
        };
    };
};

(async () => {
    if (process.env.NODE_ENV === 'dev') {
        onRebuild();
        esbuild
            .context(conf)
            .then(ctx => ctx.serve({
                servedir: outDir + '..',
                fallback: `${outDir}/index.html`,
                onRequest: () => onRebuild(),
            }))
            .then(({ host, port }) => {
                const proxyPort = +(process.env.DEV_SERVER_PORT || '3000');
                http.createServer((req, res) => {
                    if (req.method === 'POST' && req.url === '/yem/jsonrpc') {
                        let buffer = '';
                        req.on('data', chunk => buffer += chunk);
                        req.on('end', () => {
                            const data = JSON.parse(buffer);

                            res.setHeader('content-type', 'application/json');
                            res.writeHead(200);
                            setTimeout(() => {
                                if (Array.isArray(data)) {
                                    res.end(data.map(it => JSON.stringify(jsonRpcMocks(it), null, 2)));
                                } else {
                                    res.end(JSON.stringify(jsonRpcMocks(data), null, 2));
                                }
                            }, 500);
                        });
                        return;
                    }

                    const options = {
                        hostname: host,
                        port: port,
                        path: req.url,
                        method: req.method,
                        headers: req.headers,
                    };
                    req.pipe(
                        http.request(options, proxyRes => {
                            res.writeHead(proxyRes.statusCode, proxyRes.headers)
                            proxyRes.pipe(res, { end: true })
                        }),
                        { end: true });
                }).listen(proxyPort);
                console.log('localhost', proxyPort);
            });
    } else {
        esbuild
            .build(conf)
            .catch(() => process.exit(1))
            .finally(onRebuild);
    }
})();
