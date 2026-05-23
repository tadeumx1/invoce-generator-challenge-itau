package br.com.itau.invoicegenerator.adapter.web.dto;

import br.com.itau.invoicegenerator.domain.model.Address;
import br.com.itau.invoicegenerator.domain.model.Document;
import br.com.itau.invoicegenerator.domain.model.Invoice;
import br.com.itau.invoicegenerator.domain.model.InvoiceItem;
import br.com.itau.invoicegenerator.domain.model.Item;
import br.com.itau.invoicegenerator.domain.model.Order;
import br.com.itau.invoicegenerator.domain.model.Recipient;
import java.util.List;

public class WebInvoiceMapper {

  public Order toDomain(OrderDto dto) {
    return Order.builder()
        .orderId(dto.getOrderId())
        .date(dto.getDate())
        .totalItemsValue(dto.getTotalItemsValue())
        .freightValue(dto.getFreightValue())
        .items(toItems(dto.getItems()))
        .recipient(toDomain(dto.getRecipient()))
        .build();
  }

  public InvoiceDto toDto(Invoice invoice) {
    return InvoiceDto.builder()
        .invoiceId(invoice.getInvoiceId())
        .date(invoice.getDate())
        .totalItemsValue(invoice.getTotalItemsValue())
        .freightValue(invoice.getFreightValue())
        .items(toInvoiceItemDtos(invoice.getItems()))
        .recipient(toDto(invoice.getRecipient()))
        .build();
  }

  private List<Item> toItems(List<ItemDto> dtos) {
    if (dtos == null) {
      return null;
    }
    return dtos.stream().map(this::toDomain).toList();
  }

  private Item toDomain(ItemDto dto) {
    return new Item(dto.getItemId(), dto.getDescription(), dto.getUnitPrice(), dto.getQuantity());
  }

  private Recipient toDomain(RecipientDto dto) {
    if (dto == null) {
      return null;
    }
    return Recipient.builder()
        .name(dto.getName())
        .personType(dto.getPersonType())
        .taxRegime(dto.getTaxRegime())
        .documents(toDocuments(dto.getDocuments()))
        .addresses(toAddresses(dto.getAddresses()))
        .build();
  }

  private List<Document> toDocuments(List<DocumentDto> dtos) {
    if (dtos == null) {
      return null;
    }
    return dtos.stream().map(this::toDomain).toList();
  }

  private Document toDomain(DocumentDto dto) {
    return Document.builder().number(dto.getNumber()).type(dto.getType()).build();
  }

  private List<Address> toAddresses(List<AddressDto> dtos) {
    if (dtos == null) {
      return null;
    }
    return dtos.stream().map(this::toDomain).toList();
  }

  private Address toDomain(AddressDto dto) {
    return Address.builder()
        .zipCode(dto.getZipCode())
        .street(dto.getStreet())
        .number(dto.getNumber())
        .state(dto.getState())
        .complement(dto.getComplement())
        .purpose(dto.getPurpose())
        .region(dto.getRegion())
        .build();
  }

  private List<InvoiceItemDto> toInvoiceItemDtos(List<InvoiceItem> items) {
    if (items == null) {
      return null;
    }
    return items.stream().map(this::toDto).toList();
  }

  private InvoiceItemDto toDto(InvoiceItem item) {
    return InvoiceItemDto.builder()
        .itemId(item.getItemId())
        .description(item.getDescription())
        .unitPrice(item.getUnitPrice())
        .quantity(item.getQuantity())
        .itemTaxValue(item.getItemTaxValue())
        .build();
  }

  private RecipientDto toDto(Recipient recipient) {
    if (recipient == null) {
      return null;
    }
    return RecipientDto.builder()
        .name(recipient.getName())
        .personType(recipient.getPersonType())
        .taxRegime(recipient.getTaxRegime())
        .documents(toDocumentDtos(recipient.getDocuments()))
        .addresses(toAddressDtos(recipient.getAddresses()))
        .build();
  }

  private List<DocumentDto> toDocumentDtos(List<Document> documents) {
    if (documents == null) {
      return null;
    }
    return documents.stream().map(this::toDto).toList();
  }

  private DocumentDto toDto(Document document) {
    return DocumentDto.builder().number(document.getNumber()).type(document.getType()).build();
  }

  private List<AddressDto> toAddressDtos(List<Address> addresses) {
    if (addresses == null) {
      return null;
    }
    return addresses.stream().map(this::toDto).toList();
  }

  private AddressDto toDto(Address address) {
    return AddressDto.builder()
        .zipCode(address.getZipCode())
        .street(address.getStreet())
        .number(address.getNumber())
        .state(address.getState())
        .complement(address.getComplement())
        .purpose(address.getPurpose())
        .region(address.getRegion())
        .build();
  }
}
