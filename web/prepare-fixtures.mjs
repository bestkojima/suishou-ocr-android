import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';

// 仓库只维护原始样本；浏览器需要的内嵌图片在构建时生成。
// provenance 保留历史识别来源，生成过程不执行 OCR 或改写原始结果。
export async function prepareFixtures() {
  const datasets = [];
  for (const id of ['odb-13', 'odb-09']) {
    const directory = path.resolve('fixtures/replay', id);
    const provenance = JSON.parse(await readFile(path.join(directory, 'provenance.json'), 'utf8'));
    const checkedFile = async (relative, expectedHash) => {
      const filename = path.resolve(directory, relative);
      if (!filename.startsWith(directory + path.sep)) throw Error(`样本路径越界：${relative}`);
      const bytes = await readFile(filename);
      if (createHash('sha256').update(bytes).digest('hex') !== expectedHash) {
        throw Error(`样本与来源校验不一致：${id}/${relative}`);
      }
      return bytes;
    };
    const dataUrl = async (relative, expectedHash) => {
      const mime = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg' }[path.extname(relative)];
      if (!mime) throw Error(`不支持的样本图片：${relative}`);
      return `data:${mime};base64,${(await checkedFile(relative, expectedHash)).toString('base64')}`;
    };
    const document = JSON.parse(await checkedFile('document.json', provenance.source_json_sha256));
    const hashes = new Map(provenance.assets.map(asset => [asset.path, asset.sha256]));
    const assets = {};
    for (const resource of document.resources) {
      if (!hashes.has(resource.path)) continue; // 原始结果中缺失的资源保持缺失。
      assets[resource.path] = {
        src: await dataUrl(resource.path, hashes.get(resource.path)),
        width: resource.width, height: resource.height,
      };
    }
    const page = document.pages[0];
    const byId = new Map(page.blocks.map(block => [block.id, block]));
    const blocks = page.reading_order.map(id => {
      const block = byId.get(id), content = block.content;
      let markdown = content.text || '';
      if (block.type === 'image') markdown = `![插图](${content.resource})`;
      else if (content.format === 'latex') markdown = `$$\n${markdown.trim()}\n$$`;
      return {
        id, type: block.type, markdown, sourceStatus: block.status,
        resource: content.resource ?? null, bbox: block.bbox, format: content.format ?? null,
      };
    });
    datasets.push({
      id, title: provenance.title, provenance, blocks, assets,
      original: await dataUrl('source' + path.extname(provenance.original_path), provenance.original_sha256),
      rasterSize: page.raster_size,
    });
  }
  await mkdir('web/generated', { recursive: true });
  await writeFile('web/generated/replay-fixtures.json', JSON.stringify(datasets));
}
