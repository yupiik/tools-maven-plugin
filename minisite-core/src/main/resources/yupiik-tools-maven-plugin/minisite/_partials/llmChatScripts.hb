<div id="llm-chat-root" style="display:none"
     data-model-id="{{llmModelId}}"
     data-embedding-model="{{llmEmbedding.model}}"
     data-embedding-dimension="{{llmEmbedding.dimension}}"
     data-embedding-pooling="{{llmEmbedding.pooling}}"
     data-embedding-prefix="{{llmEmbedding.queryPrefix}}"
     data-retrieval-min-score="{{llmEmbedding.minScore}}"
     data-retrieval-lex-weight="{{llmEmbedding.lexWeight}}"
     data-retrieval-synonym-weight="{{llmEmbedding.synonymWeight}}"
     data-retrieval-top-k="{{llmEmbedding.topK}}"
     data-self-host="{{selfHostLlm}}"
     data-base="{{siteBase}}"></div>
<script src="{{siteBase}}/js/llm-chat.js?v={{projectVersion}}"></script>