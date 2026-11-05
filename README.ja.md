<div align="center">

[English](./README.md) | [Español](./README.es.md) | [Português](./README.pt-br.md) | [Français](./README.fr.md) | **日本語**

<img src="./.github/assets/logo.png" alt="ましろのロゴ" title="ましろ" width="120"/>

# ましろ

### Android向けマンガリーダー

マンガ、Webtoon、コミックをスマホで見つけて読もう。

**[ウェブサイトとガイド（スペイン語）](https://frey004.github.io/mashiro/)**

</div>

## ダウンロード

最新版は **[Releases](../../releases/latest)** から入手できます：

- **Mashiro-arm64-v8a.apk**: 現在のほぼすべてのスマホ向け。
- **Mashiro-universal.apk**: どのスマホでも動作（サイズ大）。

*Android 8.0 以上が必要です。*

## 機能

- 拡張機能によるローカルコンテンツとオンラインソースの閲覧。
- 設定可能なリーダー：複数の読書モード、方向、ズームなど。
- 紙のようにめくれるページ、見開き表示、章の間の3Dブック。
- MyAnimeList、AniList、Kitsu などでの記録。ライブラリ全体を AniList に一括で紐付けできます。
- カテゴリーで整理できるライブラリ。
- ライトテーマとダークテーマ（調整可能なリキッドグラステーマを含む）。
- 新しい章のためのライブラリ定期更新。
- オフラインで読むためのダウンロード。
- ライブラリのバックアップ（自分の Google ドライブにも保存可能）。

## ビルド

APK は変更のたびに GitHub Actions で自動ビルドされ、[Releases](../../releases) に公開されます。ローカルでビルドするには：`./gradlew assembleRelease`。

## 免責事項

本アプリの開発者は利用可能なコンテンツ提供元とは一切関係がなく、本アプリはいかなるコンテンツもホストしていません。

## ライセンス

ましろはオープンソースプロジェクト Mihon をベースにしており、Apache 2.0 ライセンスで配布されています。[LICENSE](./LICENSE) ファイルをご覧ください。

    Copyright © 2015 Javier Tomás
    Copyright © 2024 Mihon Open Source Project

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

---

**AIによる開発:** このフォークの変更の大部分（コード、翻訳、画像処理、ウェブサイト）は、メンテナーの指示のもと AI アシスタント（Anthropic の Claude）によって作成されました。変更は自動でビルド・確認されていますが、すべてを一行ずつレビューしたり多くの端末でテストしたりしたわけではないため、不具合がある可能性があります。ソフトウェアはライセンスに記載のとおり「現状のまま」無保証で提供されます。問題があれば [Issue を作成](../../issues)してください。
