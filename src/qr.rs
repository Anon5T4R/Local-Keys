//! Decodificação de QR de imagem (rqrr, Rust puro) para o campo TOTP do formulário — os bytes vêm de
//! um arquivo escolhido no seletor ou da área de transferência (`arboard`). Captura de tela fica fora
//! (decisão do pedido 022); aqui só se lê o que já é imagem.

/// Lado máximo (px) de uma imagem que entra na busca de QR: acima disso o decode trava o frame (e a
/// memória explode antes) — barrado pelo CABEÇALHO, sem tocar nos pixels.
const MAX_SIDE: u32 = 4096;

/// Por que não deu para ler o QR.
#[derive(Debug, PartialEq)]
pub(crate) enum QrError {
    /// Os bytes não abrem como imagem (formato desconhecido ou leitura falhou).
    NotAnImage,
    /// A imagem abriu, mas não há QR nela.
    NoQr,
    /// Imagem grande demais para procurar QR sem travar a interface.
    TooBig,
}

/// O texto do primeiro QR de uma imagem (PNG/JPEG/BMP/…), em bytes.
pub(crate) fn decode(bytes: &[u8]) -> Result<String, QrError> {
    // As dimensões vêm do cabeçalho (IHDR & cia): uma imagem gigante é rejeitada na hora, sem
    // decodificar os pixels.
    let reader = image::ImageReader::new(std::io::Cursor::new(bytes)).with_guessed_format().map_err(|_| QrError::NotAnImage)?;
    let (w, h) = reader.into_dimensions().map_err(|_| QrError::NotAnImage)?;
    if w.max(h) > MAX_SIDE {
        return Err(QrError::TooBig);
    }
    let img = image::load_from_memory(bytes).map_err(|_| QrError::NotAnImage)?;
    decode_gray(&img.to_luma8())
}

/// O texto do primeiro QR de pixels de luminância — o caminho da área de transferência, que já chega
/// decodificada de RGBA (os pixels já estão na memória; o teto evita a busca quadrática do QR).
pub(crate) fn decode_gray(gray: &image::GrayImage) -> Result<String, QrError> {
    let (w, h) = gray.dimensions();
    if w.max(h) > MAX_SIDE {
        return Err(QrError::TooBig);
    }
    let mut prepared = rqrr::PreparedImage::prepare_from_greyscale(w as usize, h as usize, |x, y| gray.get_pixel(x as u32, y as u32)[0]);
    for grid in prepared.detect_grids() {
        // O primeiro QR que decodifica vale (imagem de 2FA costuma ter um só).
        if let Ok((_, text)) = grid.decode() {
            return Ok(text);
        }
    }
    Err(QrError::NoQr)
}

/// Um PNG com o QR de `text` (qrcodegen + image), com zona silenciosa de 4 módulos — os testes geram
/// o próprio QR em vez de fixture commitada.
#[cfg(test)]
pub(crate) fn qr_png(text: &str, scale: u32) -> Vec<u8> {
    let qr = qrcodegen::QrCode::encode_text(text, qrcodegen::QrCodeEcc::Medium).expect("texto cabe no QR");
    let quiet = 4u32;
    let size = (qr.size() as u32 + quiet * 2) * scale;
    let mut img = image::GrayImage::from_pixel(size, size, image::Luma([255]));
    for y in 0..qr.size() as u32 {
        for x in 0..qr.size() as u32 {
            let ink = if qr.get_module(x as i32, y as i32) { 0 } else { 255 };
            for dy in 0..scale {
                for dx in 0..scale {
                    img.put_pixel((x + quiet) * scale + dx, (y + quiet) * scale + dy, image::Luma([ink]));
                }
            }
        }
    }
    let mut out = Vec::new();
    image::DynamicImage::ImageLuma8(img).write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).expect("PNG em memória");
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn le_o_qr_de_um_png() {
        let bytes = qr_png("otpauth://totp/GitHub:joao%40exemplo.com?secret=JBSWY3DPEHPK3PXP", 8);
        assert_eq!(decode(&bytes).as_deref(), Ok("otpauth://totp/GitHub:joao%40exemplo.com?secret=JBSWY3DPEHPK3PXP"));
        // e do cinza direto (o caminho da área de transferência)
        let img = image::load_from_memory(&bytes).expect("png").to_luma8();
        assert!(decode_gray(&img).is_ok());
    }

    #[test]
    fn imagem_sem_qr_e_bytes_de_fora() {
        // Uma imagem válida sem QR nenhum.
        let lisa = image::GrayImage::from_pixel(64, 64, image::Luma([128]));
        assert!(matches!(decode_gray(&lisa), Err(QrError::NoQr)));
        // Bytes que não são imagem.
        assert!(matches!(decode(b"isto nao e uma imagem"), Err(QrError::NotAnImage)));
        // Um QR minúsculo demais para decodificar também não trava nem entra em pânico.
        let minusculo = qr_png("otpauth://totp/X?secret=JBSWY3DPEHPK3PXP", 1);
        let _ = decode(&minusculo);
    }

    #[test]
    fn imagem_grande_e_barrada_pelo_cabecalho() {
        // 5000×3000 no cabeçalho: barrada sem decodificar pixel nenhum (o pedido da revisão — o decode
        // de uma imagem dessas travaria o frame).
        let mut png = Vec::new();
        image::DynamicImage::ImageLuma8(image::GrayImage::from_pixel(5000, 3000, image::Luma([255])))
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .expect("png");
        assert!(matches!(decode(&png), Err(QrError::TooBig)));
        // O caminho da área de transferência (pixels já em memória) tem o mesmo teto.
        let larga = image::GrayImage::from_pixel(5000, 1, image::Luma([255]));
        assert!(matches!(decode_gray(&larga), Err(QrError::TooBig)));
        // O teto exato passa (4096 é aceito — a busca roda, sem QR).
        let no_teto = image::GrayImage::from_pixel(4096, 1, image::Luma([255]));
        assert!(matches!(decode_gray(&no_teto), Err(QrError::NoQr)), "4096 ainda entra");
    }
}
