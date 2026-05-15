package com.circleguard.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


/**
 * UT-4: DualChainAuthenticationProvider — LDAP Fallback to Local DB
 *
 * Objetivo: Verificar que cuando el proveedor LDAP lanza AuthenticationException,
 * el sistema NO propaga la excepción sino que intenta autenticación local.
 *
 * Criticidad: ALTA — sin este fallback, todos los usuarios de BD local
 * (administradores, visitantes) quedan permanentemente bloqueados si LDAP falla.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UT-4: DualChainAuthenticationProvider — LDAP Fallback")
class DualChainAuthenticationProviderTest {

    @Mock
    private LdapAuthenticationProvider ldapProvider;

    @Mock
    private DaoAuthenticationProvider localProvider;

    private DualChainAuthenticationProvider dualChainProvider;

    @BeforeEach
    void setUp() {
        dualChainProvider = new DualChainAuthenticationProvider(ldapProvider, localProvider);
    }

    @Test
    @DisplayName("LDAP exitoso debe retornar autenticación sin llamar al proveedor local")
    void ldapSuccess_shouldReturnAuthWithoutCallingLocalProvider() {
        // Arrange
        Authentication inputAuth = new UsernamePasswordAuthenticationToken("ldap_user", "password");
        Authentication ldapAuth  = new UsernamePasswordAuthenticationToken("ldap_user", null, java.util.Collections.emptyList());
        when(ldapProvider.authenticate(any())).thenReturn(ldapAuth);

        // Act
        Authentication result = dualChainProvider.authenticate(inputAuth);

        // Assert
        assertNotNull(result);
        verify(ldapProvider, times(1)).authenticate(any());
        verify(localProvider, never()).authenticate(any());
    }

    @Test
    @DisplayName("Fallo LDAP debe activar fallback al proveedor local")
    void ldapFailure_shouldFallbackToLocalProvider() {
        // Arrange
        Authentication inputAuth = new UsernamePasswordAuthenticationToken("local_admin", "password");
        Authentication localAuth = new UsernamePasswordAuthenticationToken("local_admin", null, java.util.Collections.emptyList());

        when(ldapProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP server unreachable"));
        when(localProvider.authenticate(any())).thenReturn(localAuth);

        // Act
        Authentication result = dualChainProvider.authenticate(inputAuth);

        // Assert — el fallback debe activarse exactamente una vez
        assertNotNull(result, "La autenticación local DEBE tener éxito tras el fallo LDAP");
        verify(ldapProvider, times(1)).authenticate(any());
        verify(localProvider, times(1)).authenticate(any());
    }

    @Test
    @DisplayName("Ambos proveedores fallan debe propagar excepción del proveedor local")
    void bothProvidersFailure_shouldPropagateLocalException() {
        // Arrange
        Authentication inputAuth = new UsernamePasswordAuthenticationToken("unknown_user", "wrong_pass");

        when(ldapProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP: user not found"));
        when(localProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("Local DB: user not found"));

        // Act & Assert
        assertThrows(AuthenticationException.class,
                () -> dualChainProvider.authenticate(inputAuth),
                "Si ambos proveedores fallan, debe lanzarse excepción de autenticación");
    }

    @Test
    @DisplayName("supports() debe retornar true para UsernamePasswordAuthenticationToken")
    void supports_shouldAcceptUsernamePasswordAuthenticationToken() {
        assertTrue(dualChainProvider.supports(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    @DisplayName("supports() debe retornar false para tipos de autenticación no soportados")
    void supports_shouldRejectUnsupportedAuthenticationTypes() {
        assertFalse(dualChainProvider.supports(AnonymousAuthenticationToken.class));
    }
}
