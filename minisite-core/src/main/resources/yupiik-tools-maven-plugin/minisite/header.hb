<!DOCTYPE html>
<html lang="en" data-theme="dark">

<head>
    <title>{{title}}</title>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="description" content="{{description}}">
    <meta name="author" content="{{metaAuthor}}">
    <meta name="generator" content="Yupiik Minisite Generator">
    {{{metaKeywords}}}
    <link rel="shortcut icon" href="{{favicon}}">
    <meta name="theme-color" content="#0b0c0f" media="(prefers-color-scheme: dark)">
    <meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">

    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/5.15.1/css/all.min.css" integrity="sha512-+4zCK9k+qNFUR5X+cKL9EIR+ZOhtIloNl9GIKS57V1MyNsYpYcUrUeQc9vNfzsWfV28IaLL3i96P9sdNyeRssA==" crossorigin="anonymous" />
    {{#if highlightJsCss}}{{>highlightJsCss}}{{/if}}
    <link id="theme-style" rel="stylesheet" href="{{base}}/css/theme.css?v={{projectVersion}}">
    {{#if llmChat}}{{>llmChatCss}}{{/if}}
    {{{customHead}}}
<script>/* inline to avoid flicker */try{var t=localStorage.getItem('yupiik-theme');if(t==='light'||t==='dark'){document.documentElement.setAttribute('data-theme',t);}}catch(e){}</script>
</head>
<body>