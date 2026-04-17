/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  tutorialSidebar: [
    // Getting started at top level
    'getting-started',
    
    // Core concepts section with comprehensive submenu
    {
      type: 'category',
      label: 'Core Concepts',
      collapsed: false,
      link: {
        type: 'doc',
        id: 'core-concepts',
      },
      items: [
        'executor',
        'dispatcher',
        'memory',
        'invoker',
        'handling',
        'value-and-types',
        'messages',
        'files',
        'observability',
      ],
    },
    
    // LLM Providers section with submenu
    {
      type: 'category',
      label: 'LLM Providers',
      collapsed: false,
      link: {
        type: 'generated-index',
        title: 'LLM Providers',
        description: 'Integrations with LLM services',
        slug: '/llm-providers',
      },
      items: [
        'openrouter',
        'google-genai',
      ],
    },
    
    // MCP Server (standalone page)
    'mcp',
    
    // Examples section
    {
      type: 'category',
      label: 'Examples',
      collapsed: true,
      items: [
        'examples',
      ],
    },
    
    // API Reference
    {
      type: 'link',
      label: 'API Reference',
      href: 'pathname:///api/index.html',
    },
  ],
};

module.exports = sidebars;
