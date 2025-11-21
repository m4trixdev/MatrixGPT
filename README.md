# MatrixGPT - Ferramenta de administração de IA para Minecraft

Plugin de administração para Minecraft baseado em Inteligência Artificial, permitindo que administradores executem comandos usando linguagem natural.

![Minecraft Version](https://img.shields.io/badge/Minecraft-1.17--1.21.4-brightgreen)
![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Spigot-blue)
![Language](https://img.shields.io/badge/Language-Kotlin-purple)

## 📋 Características

- 🤖 **Integração com OpenAI GPT** - Execute comandos usando linguagem natural
- 💾 **Suporte a MySQL e SQLite** - Escolha o banco de dados que preferir
- ⚡ **Sistema de Cache** - Performance otimizada para evitar lag
- 🎨 **Suporte a MiniMessage** - Cores, gradientes e formatação avançada
- 🔒 **Sistema de Permissões** - Controle total sobre quem pode usar a IA
- 📊 **Histórico de Interações** - Todas as requisições são salvas no banco
- ⚠️ **Detecção de Lag** - Avisa antes de executar ações pesadas

## 🚀 Instalação

### Requisitos

- Java 21+
- Paper/Spigot 1.17 - 1.21.4
- Chave de API da OpenAI

### Passos

1. Baixe o arquivo `.jar` da release mais recente
2. Coloque na pasta `plugins/` do seu servidor
3. Inicie o servidor para gerar os arquivos de configuração
4. Configure sua chave de API no `config.yml`
5. Reinicie o servidor

## ⚙️ Configuração

### config.yml

```yaml
# Banco de Dados
database:
  type: SQLITE  # SQLITE ou MYSQL
  mysql:
    host: localhost
    port: 3306
    database: matrixgpt
    username: root
    password: senha123
  sqlite:
    file: matrixgpt.db

# API OpenAI
openai:
  api-key: "sua-chave-aqui"
  model: "gpt-4"
  max-tokens: 500
  temperature: 0.7

# Marcador de ativação
trigger:
  marker: "gpt,"
```

### Personalização de Mensagens

Todas as mensagens podem ser personalizadas com suporte a:
- Cores legacy (`&a`, `&c`, etc)
- Cores hexadecimais
- Tags MiniMessage (`<blue>`, `<bold>`, etc)
- Gradientes (`<gradient:#FF0000:#00FF00>`)

Tipos de saída disponíveis:
- `CHAT` - Mensagem no chat
- `TITLE` - Título na tela
- `ACTIONBAR` - Barra de ação

## 🎮 Uso

### Comandos

| Comando | Descrição | Permissão |
|---------|-----------|-----------|
| `/gpt on` | Ativa o sistema de IA | `matrixgpt.admin` |
| `/gpt off` | Desativa o sistema de IA | `matrixgpt.admin` |
| `/gpt reload` | Recarrega as configurações | `matrixgpt.admin.reload` |

### Como Usar a IA

1. Ative o sistema com `/gpt on`
2. No chat, escreva `gpt,` seguido do seu pedido
3. Exemplos:
   - `gpt, me coloca no gamemode criativo`
   - `gpt, dá uma espada de diamante para o João`
   - `gpt, teleporta o Pedro até mim`
   - `gpt, me dá uma picareta com eficiência 5`

### Exemplos de Comandos

```
gpt, me coloca no gamemode
→ /gamemode creative SeuNick

gpt, bota o João no survival
→ /gamemode survival João

gpt, me dá uma espada de diamante
→ /give SeuNick minecraft:diamond_sword 1

gpt, teleporta o Pedro para mim
→ /tp Pedro SeuNick
```

## 🔐 Permissões

| Permissão | Descrição | Padrão |
|-----------|-----------|--------|
| `matrixgpt.admin` | Acesso ao sistema GPT | OP |
| `matrixgpt.admin.reload` | Permite recarregar configurações | OP |

## 🛠️ Compilação

### Requisitos de Desenvolvimento

- JDK 21
- Gradle 8.3+

### Build

```bash
git clone https://github.com/m4trixdev/MatrixGPT.git
cd MatrixGPT
./gradlew shadowJar
```

O arquivo `.jar` será gerado em `build/libs/`

### Testar com Run-Paper

```bash
./gradlew runServer
```

## 📊 Banco de Dados

### Tabelas

**gpt_users**
- `uuid` - UUID do jogador
- `gpt_enabled` - Status do sistema (on/off)
- `last_updated` - Última atualização

**gpt_history**
- `id` - ID da interação
- `uuid` - UUID do jogador
- `request` - Pedido enviado
- `response` - Resposta da IA
- `timestamp` - Momento da interação

## ⚠️ Sistema de Segurança

O plugin inclui proteções contra:
- ✅ Comandos que podem causar lag extremo
- ✅ Spawning em massa de entidades
- ✅ Ações potencialmente perigosas

Quando detectado, o sistema avisa o administrador antes de executar.

## 🎨 Sistema de Cores

### Exemplos de Formatação

```yaml
# Cores Legacy
content: "&a[MatrixGPT] &7Mensagem"

# MiniMessage
content: "<green>[MatrixGPT]</green> <gray>Mensagem</gray>"

# Gradiente
content: "<gradient:#00ff00:#00aa00>MatrixGPT</gradient>"

# Negrito e Itálico
content: "<b><green>MatrixGPT</green></b> <i>Sistema ativado</i>"
```

## 🐛 Troubleshooting

### Plugin não inicia

- Verifique se está usando Java 21+
- Confirme se o `config.yml` está com sintaxe correta
- Verifique os logs do console para erros

### Erro de API

- Confirme se a chave da OpenAI está correta
- Verifique se tem créditos na sua conta OpenAI
- Teste a conectividade com a API

### Banco de dados não conecta

- **MySQL**: Verifique credenciais e se o servidor MySQL está rodando
- **SQLite**: Confirme se a pasta `plugins/MatrixGPT/` tem permissões de escrita

## 📝 Changelog

### v1.0.0
- Release inicial
- Integração com OpenAI GPT-4
- Suporte a MySQL e SQLite
- Sistema de cache otimizado
- Suporte completo a MiniMessage

## 📄 Licença

Este projeto está sob a licença MIT.

## 👨‍💻 Autor

**M4trixDev**

## 🤝 Contribuindo

Contribuições são bem-vindas! Sinta-se à vontade para:
- Reportar bugs
- Sugerir novas funcionalidades
- Enviar pull requests

## 📞 Suporte

- Issues: [GitHub Issues](https://github.com/m4trixdev/MatrixGPT/issues)
- Discord: [_devmatrix_]

---

