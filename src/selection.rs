//! A seleção de itens da lista (desktop): um conjunto ORDENADO de ids, sem UI e sem cofre.
//!
//! A tela (peça 2 desta unidade) chama [`Selection::toggle`] no Ctrl+clique, [`Selection::range`] no Shift+clique
//! com a ordem visível da lista, e [`Selection::reset_for_filter_change`] quando o filtro, a busca ou a pasta mudam —
//! a seleção não pode sobreviver a uma lista diferente da que a pessoa está vendo.

use std::collections::HashSet;

/// Os itens marcados na lista, na ordem em que foram marcados.
#[derive(Clone, Debug, Default)]
pub struct Selection {
    /// Os ids na ordem em que entraram.
    order: Vec<String>,
    /// Espelho de `order` para o `contains`, que a lista chama uma vez por linha visível.
    present: HashSet<String>,
}

impl Selection {
    pub fn new() -> Self {
        Self::default()
    }

    /// Marca o id, ou desmarca se já estava marcado (Ctrl+clique).
    pub fn toggle(&mut self, id: &str) {
        if self.present.remove(id) {
            self.order.retain(|selected| selected != id);
        } else {
            self.present.insert(id.to_string());
            self.order.push(id.to_string());
        }
    }

    /// Deixa só este id marcado (clique sem modificador).
    pub fn select_only(&mut self, id: &str) {
        self.clear();
        self.toggle(id);
    }

    /// Marca o intervalo entre dois ids na ordem visível da lista (Shift+clique), inclusive as pontas e em qualquer
    /// direção. Id que não está na lista visível (lista mudou no meio) não mexe na seleção.
    pub fn range(&mut self, from: &str, to: &str, order: &[String]) {
        let (Some(start), Some(end)) = (order.iter().position(|id| id == from), order.iter().position(|id| id == to)) else {
            return;
        };
        let (start, end) = if start <= end { (start, end) } else { (end, start) };
        self.clear();
        for id in &order[start..=end] {
            self.toggle(id);
        }
    }

    pub fn clear(&mut self) {
        self.order.clear();
        self.present.clear();
    }

    /// Trocar filtro, busca ou pasta zera a seleção: os ids marcados podem nem estar na lista nova.
    pub fn reset_for_filter_change(&mut self) {
        self.clear();
    }

    /// Mantém só os ids que passam em [visible] (a lista visível do desenho) e reconstrói o espelho. Item marcado que
    /// saiu da tela — desmarcado no filtro "Favoritos", pasta ou tipo trocados no editor, lixeira — não pode ser
    /// contado no rodapé nem apagado às cegas.
    pub fn retain_visible(&mut self, visible: impl Fn(&str) -> bool) {
        self.order.retain(|id| visible(id));
        self.present = self.order.iter().cloned().collect();
    }

    pub fn contains(&self, id: &str) -> bool {
        self.present.contains(id)
    }

    pub fn len(&self) -> usize {
        self.order.len()
    }

    pub fn is_empty(&self) -> bool {
        self.order.is_empty()
    }

    pub fn ids(&self) -> impl Iterator<Item = &str> {
        self.order.iter().map(String::as_str)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn visible(ids: &[&str]) -> Vec<String> {
        ids.iter().map(|id| id.to_string()).collect()
    }

    #[test]
    fn toggle_adiciona_no_fim_e_tira_no_segundo_clique() {
        let mut selection = Selection::new();
        selection.toggle("a");
        selection.toggle("b");
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["a", "b"]);
        assert_eq!(selection.len(), 2);
        assert!(selection.contains("b"));

        selection.toggle("a");
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["b"]);
        assert!(!selection.contains("a"));
    }

    #[test]
    fn select_only_troca_a_selecao_inteira() {
        let mut selection = Selection::new();
        selection.toggle("a");
        selection.toggle("b");

        selection.select_only("c");
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["c"]);

        selection.select_only("d");
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["d"]);
    }

    #[test]
    fn range_seleciona_o_intervalo_na_ordem_visivel() {
        let order = visible(&["a", "b", "c", "d", "e"]);
        let mut selection = Selection::new();

        selection.range("b", "d", &order);
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["b", "c", "d"]);

        // De baixo para cima é o mesmo intervalo.
        selection.range("e", "c", &order);
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["c", "d", "e"]);

        // Ponta a ponta: o id só seleciona ele mesmo.
        selection.range("a", "a", &order);
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["a"]);
    }

    #[test]
    fn range_com_id_fora_da_lista_visivel_nao_mexe_na_selecao() {
        let order = visible(&["a", "b", "c"]);
        let mut selection = Selection::new();
        selection.toggle("b");

        selection.range("a", "fantasma", &order);
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["b"]);

        selection.range("fantasma", "c", &order);
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["b"]);
    }

    #[test]
    fn trocar_filtro_ou_pasta_limpa_a_selecao() {
        let mut selection = Selection::new();
        selection.toggle("a");
        selection.toggle("b");

        selection.reset_for_filter_change();
        assert!(selection.is_empty());
        assert_eq!(selection.len(), 0);
        assert_eq!(selection.ids().count(), 0);

        // Limpar de novo não quebra nada.
        selection.reset_for_filter_change();
        assert!(selection.is_empty());
    }

    #[test]
    fn clear_limpa_a_selecao() {
        let mut selection = Selection::new();
        selection.toggle("a");

        selection.clear();
        assert!(selection.is_empty());
    }

    #[test]
    fn retain_visible_tira_da_selecao_o_item_que_saiu_da_lista() {
        let mut selection = Selection::new();
        selection.toggle("a");
        selection.toggle("b");
        selection.toggle("c");

        // O 'b' saiu da lista visível (o ★ caiu com o filtro "Favoritos" ligado, mudou de pasta no editor…).
        let visible = visible(&["a", "c"]);
        selection.retain_visible(|id| visible.iter().any(|visible_id| visible_id == id));

        assert_eq!(selection.ids().collect::<Vec<_>>(), ["a", "c"]);
        assert_eq!(selection.len(), 2);
        assert!(!selection.contains("b"));
        // O espelho acompanha: remarcar o que ficou desmarca como sempre.
        selection.toggle("a");
        assert_eq!(selection.ids().collect::<Vec<_>>(), ["c"]);
    }
}
